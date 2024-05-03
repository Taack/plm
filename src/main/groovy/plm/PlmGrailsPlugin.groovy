package plm

import grails.compiler.GrailsCompileStatic
import grails.plugins.Plugin
import taack.ui.TaackPlugin
import taack.ui.TaackPluginConfiguration

/*
TODO: put user extra configuration accessible to server to centralize configuration
 */
@GrailsCompileStatic
class PlmGrailsPlugin extends Plugin implements TaackPlugin {
    // the version or versions of Grails the plugin is designed for
    def grailsVersion = "4.0.3 > *"
    // resources that are excluded from plugin packaging
    def pluginExcludes = [
            "grails-app/views/error.gsp"
    ]

    // TODO Fill in these fields
    def title = "Plm" // Headline display name of the plugin

    def profiles = ['web']

    Closure doWithSpring() {
        { ->
            // TODO Implement runtime spring config (optional)
        }
    }

    void doWithDynamicMethods() {
        // TODO Implement registering dynamic methods to classes (optional)
    }

    void doWithApplicationContext() {
        // TODO Implement post initialization spring config (optional)
    }

    void onChange(Map<String, Object> event) {
        // TODO Implement code that is executed when any artefact that this plugin is
        // watching is modified and reloaded. The event contains: event.source,
        // event.application, event.manager, event.ctx, and event.plugin.
    }

    void onConfigChange(Map<String, Object> event) {
        // TODO Implement code that is executed when the project configuration changes.
        // The event is the same as for 'onChange'.
    }

    void onShutdown(Map<String, Object> event) {
        // TODO Implement code that is executed when the application shuts down (optional)
    }

    static final List<TaackPluginConfiguration.PluginRole> pluginRoles = [
            new TaackPluginConfiguration.PluginRole("ROLE_PLM_DIRECTOR", TaackPluginConfiguration.PluginRole.RoleRanking.DIRECTOR),
            new TaackPluginConfiguration.PluginRole("ROLE_PLM_MANAGER", TaackPluginConfiguration.PluginRole.RoleRanking.MANAGER),
            new TaackPluginConfiguration.PluginRole("ROLE_PLM_USER", TaackPluginConfiguration.PluginRole.RoleRanking.USER),
    ]

    static final TaackPluginConfiguration pluginConfiguration = new TaackPluginConfiguration("Plm",
            "/plm/plm.svg", "plm",
            new TaackPluginConfiguration.IPluginRole() {
                @Override
                List<TaackPluginConfiguration.PluginRole> getPluginRoles() {
                    pluginRoles
                }
            })

    @Override
    List<TaackPluginConfiguration> getTaackPluginControllerConfigurations() {
        [pluginConfiguration]
    }
}
