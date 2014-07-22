package org.jolokia.docker.maven;

import java.lang.reflect.Method;
import java.util.*;

import org.apache.maven.plugin.*;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.fusesource.jansi.AnsiConsole;
import org.jolokia.docker.maven.util.AuthConfig;
import org.jolokia.docker.maven.util.ImageName;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher;

/**
 * Base class for this plugin.
 *
 * @author roland
 * @since 26.03.14
 */
abstract public class AbstractDockerMojo extends AbstractMojo implements LogHandler, Contextualizable {

    // prefix used for console output
    private static final String LOG_PREFIX = "DOCKER> ";

    // Authentication related
    private static final String DOCKER_USERNAME = "docker.username";
    private static final String DOCKER_PASSWORD = "docker.password";
    private static final String DOCKER_EMAIL = "docker.email";
    private static final String DOCKER_AUTH = "docker.authToken";

    // Current maven project
    @Component
    protected MavenProject project;

    // Settings holding authentication info
    @Component
    protected Settings settings;

    // URL to docker daemon
    @Parameter(property = "docker.url",defaultValue = "http://localhost:2375")
    private String url;

    // Whether to use color
    @Parameter(property = "docker.useColor", defaultValue = "true")
    private boolean color;

    // Whether to skip docker alltogether
    @Parameter(property = "docker.skip", defaultValue = "false")
    private boolean skip;

    // Authentication information
    @Parameter
    Map authConfig;

    // ANSI escapes for various colors (or empty strings if no coloring is used)
    private String errorHlColor,infoHlColor,warnHlColor,resetColor,progressHlColor;

    // Container for looking up the SecDispatcher
    private PlexusContainer container;

    /**
     * Entry point for this plugin. It will set up the helper class and then calls {@link #executeInternal(DockerAccess)}
     * which must be implemented by subclass.
     *
     * @throws MojoExecutionException
     * @throws MojoFailureException
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!skip) {
            colorInit();
            DockerAccess access = new DockerAccessUnirest(url.replace("^tcp://", "http://"), this);
            access.start();
            try {
                executeInternal(access);
            } catch (MojoExecutionException exp) {
                throw new MojoExecutionException(errorHlColor + exp.getMessage() + resetColor, exp);
            } finally {
                access.shutdown();
            }
        }
    }

    /**
     * Hook for subclass for doing the real job
     *
     * @param dockerAccess access object for getting to the DockerServer
     * @throws MojoExecutionException
     * @throws MojoFailureException
     */
    protected abstract void executeInternal(DockerAccess dockerAccess)
            throws MojoExecutionException, MojoFailureException;

    // =============================================================================================
    // Registry for managed containers

    // Set with all registered shutdown actions
    private static Set<ShutdownAction> shutdownActions =
            Collections.synchronizedSet(new LinkedHashSet<ShutdownAction>());

    /**
     * Register a shutdown action executed during "stop"
     * @param shutdownAction action to register
     */
    protected static void registerShutdownAction(ShutdownAction shutdownAction) {
        shutdownActions.add(shutdownAction);
    }

    /**
     * Return shutdown actions in reverse registration order
     * @return registered shutdown actions
     */
    protected static List<ShutdownAction> getShutdownActions() {
        List<ShutdownAction> ret = new ArrayList<ShutdownAction>(shutdownActions);
        Collections.reverse(ret);
        return ret;
    }

    /**
     * Remove a list of shutdown actions
     * @param actions actions to remove
     */
    protected static void removeShutdownActions(List<ShutdownAction> actions) {
        shutdownActions.removeAll(actions);
    }

    // =================================================================================
    // Extract authentication information

    protected AuthConfig prepareAuthConfig(String image) throws MojoFailureException {
        Properties props = project.getProperties();
        if (props.containsKey(DOCKER_USERNAME) || props.containsKey(DOCKER_PASSWORD)) {
            return getAuthConfigFromProperties(props);
        }
        if (authConfig != null) {
            return getAuthConfigFromPluginConfiguration();
        }
        return getAuthConfigFromSettings(image);
    }

    private AuthConfig getAuthConfigFromProperties(Properties props) throws MojoFailureException {
        if (!props.containsKey(DOCKER_USERNAME)) {
            throw new MojoFailureException("No " + DOCKER_USERNAME + " given when using authentication");
        }
        if (!props.containsKey(DOCKER_PASSWORD)) {
            throw new MojoFailureException("No " + DOCKER_PASSWORD + " provided for username " + props.getProperty(DOCKER_USERNAME));
        }
        return new AuthConfig(props.getProperty(DOCKER_USERNAME),
                              props.getProperty(DOCKER_PASSWORD),
                              props.getProperty(DOCKER_EMAIL),
                              props.getProperty(DOCKER_AUTH));
    }

    private AuthConfig getAuthConfigFromPluginConfiguration() throws MojoFailureException {
        for (String key : new String[] { "username", "password"}) {
            if (!authConfig.containsKey(key)) {
                throw new MojoFailureException("No '" + key + "' given while using <authConfig> in configuration");
            }
        }
        return new AuthConfig(authConfig);
    }

    private AuthConfig getAuthConfigFromSettings(String image) throws MojoFailureException {
        String registry = getRegistryFromImageNameOrDefault(image);
        Server server = settings.getServer(registry);
        if (server != null) {
            return new AuthConfig(
                    server.getUsername(),
                    decrypt(registry, server.getPassword()),
                    extractFromServerConfiguration(server.getConfiguration(), "email"),
                    extractFromServerConfiguration(server.getConfiguration(), "auth")
            );
        }
        return null;
    }

    private String decrypt(String registry, String password) throws MojoFailureException {
        try {
            // Done by reflection since I have classloader issues otherwise
            Object secDispatcher = container.lookup(SecDispatcher.ROLE, "maven");
            Method method = secDispatcher.getClass().getMethod("decrypt",String.class);
            return (String) method.invoke(secDispatcher,password);
        } catch (ComponentLookupException e) {
            throw new MojoFailureException("Error looking security dispatcher");
        } catch (ReflectiveOperationException e) {
            throw new MojoFailureException("Cannot decrypt password for registry " + registry + ": " + e);
        }
    }

    @Override
    public void contextualize(Context context) throws ContextException {
        container = (PlexusContainer) context.get( PlexusConstants.PLEXUS_KEY );
    }

    private String extractFromServerConfiguration(Object configuration, String prop) {
        if (configuration != null) {
            Xpp3Dom dom = (Xpp3Dom) configuration;
            Xpp3Dom element = dom.getChild(prop);
            if (element != null) {
                return element.getValue();
            }
        }
        return null;
    }

    private String getRegistryFromImageNameOrDefault(String image) {
        ImageName name = new ImageName(image);
        return name.getRegistry() != null ? name.getRegistry() : "registry.hub.docker.io";
    }

    // =================================================================================

    // Color init
    private void colorInit() {
        if (color && System.console() != null) {
            AnsiConsole.systemInstall();
            errorHlColor = "\u001B[0;31m";
            infoHlColor = "\u001B[0;32m";
            resetColor = "\u001B[0;39m";
            warnHlColor = "\u001B[0;33m";
            progressHlColor = "\u001B[0;36m";
        } else {
            errorHlColor = infoHlColor = resetColor = warnHlColor = progressHlColor = "";
        }
    }

    /** {@inheritDoc} */
    public void debug(String message) {
        getLog().debug(LOG_PREFIX + message);
    }
    /** {@inheritDoc} */
    public void info(String info) {
        getLog().info(infoHlColor + LOG_PREFIX + info + resetColor);
    }
    /** {@inheritDoc} */
    public void warn(String warn) {
        getLog().warn(warnHlColor + LOG_PREFIX + warn + resetColor);
    }
    /** {@inheritDoc} */
    public boolean isDebugEnabled() {
        return getLog().isDebugEnabled();
    }
    /** {@inheritDoc} */
    public void error(String error) {
        getLog().error(errorHlColor + error + resetColor);
    }

    int oldProgress = 0;
    int total = 0;

    /** {@inheritDoc} */
    public void progressStart(int t) {
        System.out.print(progressHlColor + "       ");
        oldProgress = 0;
        total = t;
    }

    /** {@inheritDoc} */
    public void progressUpdate(int current) {
        System.out.print("=");
        int newProgress = (current * 10 + 5) / total;
        if (newProgress > oldProgress) {
            System.out.print(" " + newProgress + "0% ");
            oldProgress = newProgress;
        }
        System.out.flush();
    }

    /** {@inheritDoc} */
    public void progressFinished() {
        System.out.println(resetColor);
        oldProgress = 0;
        total = 0;
    }



    // ==========================================================================================
    // Class for registering a shutdown action

    protected static class ShutdownAction {

        // The image used
        private String image;

        // Data container create from image
        private String container;

        // Name of the data image create on-the-fly
        private String dataImage;

        protected ShutdownAction(String image, String container, String dataImage) {
            this.image = image;
            this.container = container;
            this.dataImage = dataImage;
        }

        /**
         * Check whether this shutdown actions applies to the given image and/or container
         *
         * @param pImage image to check
         * @return true if this action should be applied
         */
        public boolean applies(String pImage) {
            return pImage == null || pImage.equals(image);
        }

        /**
         * Clean up according to the given parameters
         *
         * @param access access object for reaching docker
         * @param log logger to use
         * @param keepContainer whether to keep the container (and its data container)
         * @param keepData whether to keep the data
         */
        public void shutdown(DockerAccess access, LogHandler log,
                             boolean keepContainer, boolean keepData) throws MojoExecutionException {
            // Stop the container
            access.stopContainer(container);
            if (!keepContainer) {
                // Remove the container
                access.removeContainer(container);
                if (dataImage != null && !keepData) {
                    removeDataImageAndItsContainers(dataImage,access,log);
                }
            }
            log.info("Stopped " + container.substring(0, 12) + (keepContainer ? "" : " and removed") + " container");
        }

        private void removeDataImageAndItsContainers(String imageToRemove, DockerAccess access, LogHandler log) throws MojoExecutionException {
            List<String> containers = access.getContainersForImage(imageToRemove);
            for (String container : containers) {
                access.removeContainer(container);
                log.info("Removed data container " + container);
            }
            access.removeImage(dataImage);
            log.info("Removed data image " + imageToRemove);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ShutdownAction that = (ShutdownAction) o;

            if (!container.equals(that.container)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            return container.hashCode();
        }
    }
}