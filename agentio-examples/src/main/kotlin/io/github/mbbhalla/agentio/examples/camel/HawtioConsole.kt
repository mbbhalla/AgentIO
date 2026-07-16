package io.github.mbbhalla.agentio.examples.camel

import io.hawt.embedded.Main
import org.slf4j.LoggerFactory

/**
 * Boots an embedded [Hawtio](https://hawt.io) web console (with its bundled Jolokia agent) in the
 * same JVM as a running Camel route, so the route can be visualized live in a browser during a
 * demo. The browser downloads the Hawtio SPA from this WAR, then talks to the bundled Jolokia
 * servlet over HTTP/JSON; Jolokia reads the Camel MBeans (enabled by the `camel-management`
 * dependency) to render the route diagram plus per-node message counts and timings.
 *
 * This is a demo aid, not production wiring: authentication is disabled and the console is served
 * on localhost only. It is deliberately fail-soft — if the Hawtio WAR path is not supplied (the
 * runner was started outside the Gradle task that resolves it), it logs a hint and returns rather
 * than aborting the route.
 */
internal object HawtioConsole {
    private val LOG = LoggerFactory.getLogger(HawtioConsole::class.java)

    /**
     * System property carrying the resolved `hawtio-war` file path. The Gradle `JavaExec` tasks
     * resolve the WAR from a dedicated configuration and pass it as `-Dhawtio.war=<path>`.
     */
    private const val WAR_PROPERTY = "hawtio.war"

    /** Default port the embedded console binds to. */
    const val DEFAULT_PORT: Int = 8080

    /** Context path the console is served under, e.g. http://localhost:8080/hawtio. */
    private const val CONTEXT_PATH = "/hawtio"

    /**
     * Starts the console on [port], on a dedicated daemon thread so it never blocks the caller's
     * route. Returns the URL the console is served on, or `null` if it could not be started (most
     * commonly because the WAR path was not supplied).
     */
    fun start(port: Int = DEFAULT_PORT): String? {
        val warPath: String? = System.getProperty(WAR_PROPERTY)
        if (warPath.isNullOrBlank()) {
            LOG.warn(
                "Hawtio WAR path not set (-D{}); skipping the web console. " +
                    "Launch via the Gradle run task to enable route visualization.",
                WAR_PROPERTY,
            )
            return null
        }

        // Local demo console: skip the login screen so it is usable out of the box.
        System.setProperty("hawtio.authenticationEnabled", "false")

        val url = "http://localhost:$port$CONTEXT_PATH"
        Thread {
            runCatching {
                val server =
                    Main().apply {
                        setWar(warPath)
                        setPort(port)
                        setContextPath(CONTEXT_PATH)
                    }
                server.run()
            }.onFailure { error ->
                LOG.error("Failed to start the embedded Hawtio console: {}", error.message, error)
            }
        }.apply {
            name = "hawtio-embedded"
            isDaemon = true
            start()
        }

        LOG.info("Hawtio console starting at {} (open the Camel tab to see the live route).", url)
        return url
    }
}
