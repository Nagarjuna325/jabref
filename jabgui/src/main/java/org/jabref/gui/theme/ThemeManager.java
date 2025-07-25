package org.jabref.gui.theme;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.jabref.gui.WorkspacePreferences;
import org.jabref.gui.icon.IconTheme;
import org.jabref.gui.util.BindingsHelper;
import org.jabref.gui.util.UiTaskExecutor;
import org.jabref.logic.l10n.Localization;
import org.jabref.model.util.FileUpdateListener;
import org.jabref.model.util.FileUpdateMonitor;

import com.google.common.annotations.VisibleForTesting;
import com.pixelduke.window.ThemeWindowManager;
import com.pixelduke.window.ThemeWindowManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs and manages style files and provides live reloading. JabRef provides two inbuilt themes and a user
 * customizable one: Light, Dark and Custom. The Light theme is basically the base.css theme. Every other theme is
 * loaded as an addition to base.css.
 * <p>
 * For type Custom, Theme will protect against removal of the CSS file, degrading as gracefully as possible. If the file
 * becomes unavailable while the application is running, some Scenes that have not yet had the CSS installed may not be
 * themed. The PreviewViewer, which uses WebEngine, supports data URLs and so generally is not affected by removal of
 * the file; however Theme package will not attempt to URL-encode large style sheets so as to protect memory usage (see
 * {@link StyleSheetFile#MAX_IN_MEMORY_CSS_LENGTH}).
 *
 * @see <a href="https://docs.jabref.org/advanced/custom-themes">Custom themes</a> in the Jabref documentation.
 */
public class ThemeManager {

    public static Map<String, Node> getDownloadIconTitleMap = Map.of(
            Localization.lang("Downloading"), IconTheme.JabRefIcons.DOWNLOAD.getGraphicNode()
    );

    private static final Logger LOGGER = LoggerFactory.getLogger(ThemeManager.class);

    private final WorkspacePreferences workspacePreferences;
    private final FileUpdateMonitor fileUpdateMonitor;
    private final Consumer<Runnable> updateRunner;
    private final ThemeWindowManager themeWindowManager;

    private final StyleSheet baseStyleSheet;
    private Theme theme;
    private boolean isDarkMode;

    private Scene mainWindowScene;
    private final Set<WebEngine> webEngines = Collections.newSetFromMap(new WeakHashMap<>());

    public ThemeManager(WorkspacePreferences workspacePreferences,
                        FileUpdateMonitor fileUpdateMonitor,
                        Consumer<Runnable> updateRunner) {
        this.workspacePreferences = Objects.requireNonNull(workspacePreferences);
        this.fileUpdateMonitor = Objects.requireNonNull(fileUpdateMonitor);
        this.updateRunner = Objects.requireNonNull(updateRunner);
        // Always returns something even if the native library is not available - see https://github.com/dukke/FXThemes/issues/15
        this.themeWindowManager = ThemeWindowManagerFactory.create();

        this.baseStyleSheet = StyleSheet.create(Theme.BASE_CSS).get();
        this.theme = workspacePreferences.getTheme();
        this.isDarkMode = Theme.EMBEDDED_DARK_CSS.equals(this.theme.getName());

        initializeWindowThemeUpdater(this.isDarkMode);

        // Watching base CSS only works in development and test scenarios, where the build system exposes the CSS as a
        // file (e.g. for Gradle run task it will be in build/resources/main/org/jabref/gui/Base.css)
        addStylesheetToWatchlist(this.baseStyleSheet, this::baseCssLiveUpdate);
        baseCssLiveUpdate();

        BindingsHelper.subscribeFuture(workspacePreferences.themeProperty(), theme -> updateThemeSettings());
        BindingsHelper.subscribeFuture(workspacePreferences.themeSyncOsProperty(), theme -> updateThemeSettings());
        BindingsHelper.subscribeFuture(workspacePreferences.shouldOverrideDefaultFontSizeProperty(), should -> updateFontSettings());
        BindingsHelper.subscribeFuture(workspacePreferences.mainFontSizeProperty(), size -> updateFontSettings());
        BindingsHelper.subscribeFuture(Platform.getPreferences().colorSchemeProperty(), colorScheme -> updateThemeSettings());
        updateThemeSettings();
    }

    private void initializeWindowThemeUpdater(boolean darkMode) {
        this.isDarkMode = darkMode;

        ListChangeListener<Window> windowsListener = change -> {
            while (change.next()) {
                if (!change.wasAdded()) {
                    continue;
                }
                change.getAddedSubList().stream()
                      .filter(Stage.class::isInstance)
                      .map(Stage.class::cast)
                      .forEach(stage -> stage.showingProperty()
                                         .addListener(_ -> applyDarkModeToWindow(stage, isDarkMode)));
            }
        };

        Window.getWindows().addListener(windowsListener);
        applyDarkModeToAllWindows(darkMode);

        LOGGER.debug("Window theme monitoring initialized");
    }

    private void applyDarkModeToWindow(Stage stage, boolean darkMode) {
        if (stage == null || !stage.isShowing()) {
            return;
        }

        try {
            themeWindowManager.setDarkModeForWindowFrame(stage, darkMode);
            LOGGER.debug("Applied {} mode to window: {}", darkMode ? "dark" : "light", stage);
        } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
            // We need to handle these exceptions because the native library may not be available on all platforms (e.g., x86).
            // See https://github.com/dukke/FXThemes/issues/13 for details.
            LOGGER.debug("Failed to set dark mode for window frame (likely due to native library compatibility issues on intel)", e);
        }
    }

    private void applyDarkModeToAllWindows(boolean darkMode) {
        this.isDarkMode = darkMode;
        Window.getWindows().stream()
              .filter(Window::isShowing)
              .filter(window -> window instanceof Stage)
              .map(window -> (Stage) window)
              .forEach(stage -> applyDarkModeToWindow(stage, darkMode));
    }

    private void updateThemeSettings() {
        Theme newTheme = Objects.requireNonNull(workspacePreferences.getTheme());

        if (workspacePreferences.themeSyncOsProperty().getValue()) {
            if (Platform.getPreferences().getColorScheme() == ColorScheme.DARK) {
                newTheme = Theme.dark();
            } else {
                newTheme = Theme.light();
            }
        }

        if (newTheme.equals(theme)) {
            LOGGER.info("Not updating theme because it hasn't changed");
        } else {
            theme.getAdditionalStylesheet().ifPresent(this::removeStylesheetFromWatchList);
        }

        this.theme = newTheme;
        LOGGER.info("Theme set to {} with base css {}", newTheme, baseStyleSheet);

        boolean isDarkTheme = Theme.EMBEDDED_DARK_CSS.equals(newTheme.getName());
        if (this.isDarkMode != isDarkTheme) {
            this.isDarkMode = isDarkTheme;
            applyDarkModeToAllWindows(isDarkTheme);
        }

        this.theme.getAdditionalStylesheet().ifPresent(
                styleSheet -> addStylesheetToWatchlist(styleSheet, this::additionalCssLiveUpdate));

        additionalCssLiveUpdate();
        updateFontSettings();
    }

    private void updateFontSettings() {
        UiTaskExecutor.runInJavaFXThread(() -> updateRunner.accept(() -> updateFontStyle(mainWindowScene)));
    }

    private void removeStylesheetFromWatchList(StyleSheet styleSheet) {
        Path oldPath = styleSheet.getWatchPath();
        if (oldPath != null) {
            fileUpdateMonitor.removeListener(oldPath, this::additionalCssLiveUpdate);
            LOGGER.info("No longer watch css {} for live updates", oldPath);
        }
    }

    private void addStylesheetToWatchlist(StyleSheet styleSheet, FileUpdateListener updateMethod) {
        Path watchPath = styleSheet.getWatchPath();
        if (watchPath != null) {
            try {
                fileUpdateMonitor.addListenerForFile(watchPath, updateMethod);
                LOGGER.info("Watching css {} for live updates", watchPath);
            } catch (IOException e) {
                LOGGER.warn("Cannot watch css path {} for live updates", watchPath, e);
            }
        }
    }

    private void baseCssLiveUpdate() {
        baseStyleSheet.reload();
        if (baseStyleSheet.getSceneStylesheet() == null) {
            LOGGER.error("Base stylesheet does not exist.");
        } else {
            LOGGER.debug("Updating base CSS for main window scene");
        }

        UiTaskExecutor.runInJavaFXThread(() -> updateRunner.accept(this::updateBaseCss));
    }

    private void additionalCssLiveUpdate() {
        final String newStyleSheetLocation = this.theme.getAdditionalStylesheet().map(styleSheet -> {
            styleSheet.reload();
            return styleSheet.getWebEngineStylesheet();
        }).orElse("");

        LOGGER.debug("Updating additional CSS for main window scene and {} web engines", webEngines.size());

        UiTaskExecutor.runInJavaFXThread(() ->
                updateRunner.accept(() -> {
                    updateAdditionalCss();

                    webEngines.forEach(webEngine -> {
                        // force refresh by unloading style sheet, if the location hasn't changed
                        if (newStyleSheetLocation.equals(webEngine.getUserStyleSheetLocation())) {
                            webEngine.setUserStyleSheetLocation(null);
                        }
                        webEngine.setUserStyleSheetLocation(newStyleSheetLocation);
                    });
                })
        );
    }

    private void updateBaseCss() {
        if (mainWindowScene == null) {
            return;
        }

        List<String> stylesheets = mainWindowScene.getStylesheets();
        if (!stylesheets.isEmpty()) {
            stylesheets.removeFirst();
        }

        stylesheets.addFirst(baseStyleSheet.getSceneStylesheet().toExternalForm());
    }

    private void updateAdditionalCss() {
        if (mainWindowScene == null) {
            return;
        }

        mainWindowScene.getStylesheets().setAll(List.of(
                baseStyleSheet.getSceneStylesheet().toExternalForm(),
                theme.getAdditionalStylesheet().map(styleSheet -> {
                         URL stylesheetUrl = styleSheet.getSceneStylesheet();
                         if (stylesheetUrl != null) {
                             return stylesheetUrl.toExternalForm();
                         } else {
                             return "";
                         }
                     })
                     .orElse("")
        ));
    }

    /**
     * Installs the base css file as a stylesheet in the given scene. Changes in the css file lead to a redraw of the
     * scene using the new css file.
     *
     * @param mainWindowScene the scene to install the css into
     */
    public void installCss(Scene mainWindowScene) {
        Objects.requireNonNull(mainWindowScene, "scene is required");
        updateRunner.accept(() -> {
            this.mainWindowScene = mainWindowScene;
            updateBaseCss();
            updateAdditionalCss();
        });
    }

    /**
     * Installs the css file as a stylesheet in the given web engine. Changes in the css file lead to a redraw of the
     * web engine using the new css file.
     *
     * @param webEngine the web engine to install the css into
     */
    public void installCss(WebEngine webEngine) {
        updateRunner.accept(() -> {
            if (this.webEngines.add(webEngine)) {
                webEngine.setUserStyleSheetLocation(this.theme.getAdditionalStylesheet().isPresent() ?
                        this.theme.getAdditionalStylesheet().get().getWebEngineStylesheet() : "");
            }
        });
    }

    /**
     * Updates the font size settings of a scene. This method needs to be called from every custom dialog constructor,
     * since javafx overwrites the style if applied before showing the dialog
     *
     * @param scene is the scene, the font size should be applied to
     */
    public void updateFontStyle(Scene scene) {
        if (scene == null) {
            return;
        }

        if (workspacePreferences.shouldOverrideDefaultFontSize()) {
            scene.getRoot().setStyle("-fx-font-size: " + workspacePreferences.getMainFontSize() + "pt;");
        } else {
            scene.getRoot().setStyle("-fx-font-size: " + workspacePreferences.getDefaultFontSize() + "pt;");
        }
    }

    /**
     * @return the currently active theme
     */
    @VisibleForTesting
    Theme getActiveTheme() {
        return this.theme;
    }
}
