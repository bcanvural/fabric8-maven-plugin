/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package io.fabric8.maven.enricher.standard;

import java.util.Arrays;
import java.util.Collections;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.kubernetes.api.model.KubernetesListBuilder;
import io.fabric8.maven.core.config.ProcessorConfig;
import io.fabric8.maven.core.util.KubernetesResourceUtil;
import io.fabric8.maven.docker.config.BuildImageConfiguration;
import io.fabric8.maven.docker.config.ImageConfiguration;
import io.fabric8.maven.enricher.api.EnricherContext;
import mockit.Expectations;
import mockit.Mocked;
import mockit.integration.junit4.JMockit;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.jayway.jsonpath.matchers.JsonPathMatchers.*;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.junit.Assert.*;

/**
 * @author roland
 * @since 03/06/16
 */
@RunWith(JMockit.class)
public class DefaultServiceEnricherTest {

    @Mocked
    private EnricherContext context;

    @Mocked
    ImageConfiguration imageConfiguration;

    @Test
    public void checkDefaultConfiguration() throws Exception {
        setupExpectations("type", "LoadBalancer");

        String json = enrich();
        assertThat(json, isJson());
        assertThat(json, hasJsonPath("$.spec.type", equalTo("LoadBalancer")));
        assertPort(json, 0, 80, 80, "http", "TCP");
        assertThat(json, hasJsonPath("$.spec.ports[*]", hasSize(1)));
    }

    @Test
    public void portOverride() throws JsonProcessingException {
        setupExpectations("port", "8080", "multiPort", "true");

        String json = enrich();
        assertPort(json, 0, 8080, 80, "http", "TCP");
        assertPort(json, 1, 53, 53, "domain", "UDP");
        assertThat(json, hasJsonPath("$.spec.ports[*]", hasSize(2)));
    }

    @Test
    public void portOverrideWithMapping() throws JsonProcessingException {
        setupExpectations("port", "443:8181/udp", "multiPort", "true");

        String json = enrich();
        assertPort(json, 0, 443, 8181, "https", "UDP");
        assertPort(json, 1, 53, 53, "domain", "UDP");
        assertThat(json, hasJsonPath("$.spec.ports[*]", hasSize(2)));
    }

    @Test
    public void portConfigWithMultipleMappings() throws Exception {
        setupExpectations("port", "443:81,853:53", "multiPort", "true");
        String json = enrich();
        assertPort(json, 0, 443, 81, "https", "TCP");
        assertPort(json, 1, 853, 53, "domain-s", "TCP");
        assertThat(json, hasJsonPath("$.spec.ports[*]", hasSize(2)));
    }

    @Test
    public void portConfigWithMultipleMappingsNoMultiPort() throws Exception {
        setupExpectations("port", "443:81,853:53", "multiPort", "false");
        String json = enrich();
        assertPort(json, 0, 443, 81, "https", "TCP");
        assertThat(json, hasJsonPath("$.spec.ports[*]", hasSize(1)));
    }

    @Test
    public void portConfigWithMultipleMappingsNoMultiPortNoImagePort() throws Exception {
        setupExpectations(false, "port", "443:81,853:53", "multiPort", "false");
        String json = enrich();
        assertPort(json, 0, 443, 81, "https", "TCP");
        assertThat(json, hasJsonPath("$.spec.ports[*]", hasSize(1)));
    }

    @Test
    public void portConfigWithMortPortsThanImagePorts() throws Exception {
        setupExpectations("port", "443:81,853:53,22/udp", "multiPort", "true");
        String json = enrich();
        assertPort(json, 0, 443, 81, "https", "TCP");
        assertPort(json, 1, 853, 53, "domain-s", "TCP");
        assertPort(json, 2, 22, 22, "ssh", "UDP");
        assertThat(json, hasJsonPath("$.spec.ports[*]", hasSize(3)));

    }

    @Test
    public void portConfigWithMortPortsThanImagePortsAndNoMultiPort() throws Exception {
        setupExpectations("port", "443:81,853:53,22");
        String json = enrich();
        assertPort(json, 0, 443, 81, "https", "TCP");
        assertThat(json, hasJsonPath("$.spec.ports[*]", hasSize(1)));
    }

    @Test
    public void portConfigWithoutPortsFromImageConfig() throws Exception {
        setupExpectations(false, "port", "443:81,853:53/UdP,22/TCP", "multiPort", "true");
        String json = enrich();
        assertPort(json, 0, 443, 81, "https", "TCP");
        assertPort(json, 1, 853, 53, "domain-s", "UDP");
        assertPort(json, 2, 22, 22, "ssh", "TCP");
        assertThat(json, hasJsonPath("$.spec.ports[*]", hasSize(3)));
    }

    @Test
    public void headlessServicePositive() throws Exception {
        setupExpectations(false, "headless", "true");
        String json = enrich();
        assertThat(json, hasNoJsonPath("$.spec.ports[*]"));
        assertThat(json, hasJsonPath("$.spec.clusterIP", equalTo("None")));

    }

    @Test
    public void headlessServiceNegative() throws Exception {
        setupExpectations(false, "headless", "false");
        DefaultServiceEnricher serviceEnricher = new DefaultServiceEnricher(context);
        KubernetesListBuilder builder = new KubernetesListBuilder();
        serviceEnricher.addMissingResources(builder);

        // Validate that the generated resource contains
        KubernetesList list = builder.build();
        assertEquals(list.getItems().size(),0);
    }

    @Test
    public void miscConfiguration() throws Exception {
        setupExpectations("headless", "true", "type", "NodePort", "expose", "true");
        String json = enrich();
        assertThat(json, hasJsonPath("$.spec.type", equalTo("NodePort")));
        assertThat(json, hasJsonPath("$.metadata.labels.expose", equalTo("true")));
        assertThat(json, hasNoJsonPath("$.spec.clusterIP"));

    }

    // ======================================================================================================

    private String enrich() throws com.fasterxml.jackson.core.JsonProcessingException {
        // Enrich
        DefaultServiceEnricher serviceEnricher = new DefaultServiceEnricher(context);
        KubernetesListBuilder builder = new KubernetesListBuilder();
        serviceEnricher.addMissingResources(builder);

        // Validate that the generated resource contains
        KubernetesList list = builder.build();
        assertEquals(list.getItems().size(),1);

        return KubernetesResourceUtil.toJson(list.getItems().get(0));
    }


    private void assertPort(String json, int idx, int port, int targetPort, String name, String protocol) {
        assertThat(json, isJson());
        assertThat(json, hasJsonPath("$.spec.ports[" + idx + "].port", equalTo(port)));
        assertThat(json, hasJsonPath("$.spec.ports[" + idx + "].targetPort", equalTo(targetPort)));
        assertThat(json, hasJsonPath("$.spec.ports[" + idx + "].name", equalTo(name)));
        assertThat(json, hasJsonPath("$.spec.ports[" + idx + "].protocol", equalTo(protocol)));
    }

    private void setupExpectations(String ... configParams) {
        setupExpectations(true, configParams);
    }

    private void setupExpectations(final boolean withPorts, String ... configParams) {
        // Setup mock behaviour
        final TreeMap config = new TreeMap();
        for (int i = 0; i < configParams.length; i += 2) {
                config.put(configParams[i],configParams[i+1]);
        }

        new Expectations() {{

            context.getConfig();
            result = new ProcessorConfig(null, null, Collections.singletonMap("fmp-service", config));

            imageConfiguration.getBuildConfiguration();
            result = getBuildConfig(withPorts);

            context.getImages();
            result = Arrays.asList(imageConfiguration);
        }};
    }

    private BuildImageConfiguration getBuildConfig(boolean withPorts) {
        // Setup a sample docker build configuration
        BuildImageConfiguration.Builder builder = new BuildImageConfiguration.Builder();
        if (withPorts) {
            builder.ports(Arrays.asList("80", "53/UDP"));
        }
        return builder.build();
    }


}
