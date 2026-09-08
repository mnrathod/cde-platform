package com.cde.platform.fetch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.util.List;

/**
 * Wires the fetch path, and only when a deployment has asked for it.
 *
 * <p>{@code cde.fetch.enabled} defaults to false, so an air-gapped or
 * PROTECTED deployment (§6.4–6.6) gets no outbound HTTP client at all rather
 * than one that is present and trusted not to be called. Absent beats
 * disabled: there is no code path to reach.
 */
@Configuration
@EnableConfigurationProperties(FetchProperties.class)
@ConditionalOnProperty(prefix = "cde.fetch", name = "enabled", havingValue = "true")
public class FetchConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FetchConfiguration.class);

    @Bean
    public FetchDestinationPolicy fetchDestinationPolicy(FetchProperties properties) {
        if (properties.getPermittedHosts().isEmpty()) {
            log.warn("Integrator-supplied URLs may name any public host: "
                     + "cde.fetch.permitted-hosts is empty. Address rules still "
                     + "refuse this deployment's own network, but naming the "
                     + "storage hosts is what closes the DNS rebinding window.");
        }
        return new FetchDestinationPolicy(
            properties.isRequireTls(), properties.getPermittedHosts());
    }

    /**
     * The real check: the policy, asking real DNS.
     *
     * <p>The resolver is a lambda rather than a method reference to
     * {@code InetAddress::getAllByName} because the policy takes a list and
     * the JDK returns an array; the adaptation is the whole body.
     */
    @Bean
    public DestinationCheck destinationCheck(FetchDestinationPolicy policy) {
        return (URI target) -> policy.checkPermitted(target, FetchConfiguration::resolve);
    }

    private static List<InetAddress> resolve(String host) throws UnknownHostException {
        return List.of(InetAddress.getAllByName(host));
    }

    /**
     * Redirects are not followed. A redirect names a second destination that
     * no policy check has seen, which is the standard way an allow-listed URL
     * becomes the cloud metadata endpoint.
     */
    @Bean
    public HttpClient fetchHttpClient(FetchProperties properties) {
        return HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(properties.getConnectTimeout())
            .build();
    }

    @Bean
    public RemoteContentFetcher remoteContentFetcher(HttpClient fetchHttpClient,
                                                     DestinationCheck destinationCheck,
                                                     FetchProperties properties) {
        return new RemoteContentFetcher(fetchHttpClient, destinationCheck, properties);
    }
}
