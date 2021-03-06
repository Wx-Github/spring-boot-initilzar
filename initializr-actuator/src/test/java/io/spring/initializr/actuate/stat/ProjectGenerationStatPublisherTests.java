/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.spring.initializr.actuate.stat;

import java.util.Collections;
import java.util.UUID;

import io.spring.initializr.actuate.stat.StatsProperties.Elastic;
import io.spring.initializr.generator.ProjectGeneratedEvent;
import io.spring.initializr.generator.ProjectRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Tests for {@link ProjectGenerationStatPublisher}.
 *
 * @author Stephane Nicoll
 */
class ProjectGenerationStatPublisherTests extends AbstractInitializrStatTests {

	private RetryTemplate retryTemplate;

	private ProjectGenerationStatPublisher statPublisher;

	private MockRestServiceServer mockServer;

	@BeforeEach
	public void setUp() {
		configureService(createProperties());
	}

	private void configureService(StatsProperties properties) {
		ProjectRequestDocumentFactory documentFactory = new ProjectRequestDocumentFactory(
				createProvider(getMetadata()));
		this.retryTemplate = new RetryTemplate();
		this.statPublisher = new ProjectGenerationStatPublisher(documentFactory,
				properties, new RestTemplateBuilder(), this.retryTemplate);
		this.mockServer = MockRestServiceServer
				.createServer(this.statPublisher.getRestTemplate());
	}

	@Test
	void publishDocumentWithUserNameAndPassword() {
		StatsProperties properties = new StatsProperties();
		properties.getElastic().setUri("http://example.com/elastic");
		properties.getElastic().setUsername("foo");
		properties.getElastic().setPassword("bar");
		configureService(properties);
		testAuthorization("http://example.com/elastic/initializr/request",
				header("Authorization", "Basic Zm9vOmJhcg=="));
	}

	@Test
	void publishDocumentWithUserInfo() {
		StatsProperties properties = new StatsProperties();
		properties.getElastic().setUri("https://elastic:secret@es.example.com");
		configureService(properties);
		testAuthorization("https://es.example.com/initializr/request",
				header("Authorization", "Basic ZWxhc3RpYzpzZWNyZXQ="));
	}

	@Test
	void publishDocumentWithUserInfoOverridesUserNamePassword() {
		StatsProperties properties = new StatsProperties();
		properties.getElastic().setUri("https://elastic:secret@es.example.com");
		properties.getElastic().setUsername("another");
		properties.getElastic().setPassword("ignored-secret");
		configureService(properties);
		testAuthorization("https://es.example.com/initializr/request",
				header("Authorization", "Basic ZWxhc3RpYzpzZWNyZXQ="));
	}

	@Test
	void publishDocumentWithNoAuthentication() {
		StatsProperties properties = new StatsProperties();
		properties.getElastic().setUri("https://example.com/test/");
		configureService(properties);
		testAuthorization("https://example.com/test/initializr/request",
				(request) -> assertThat(request.getHeaders().containsKey("Authorization"))
						.isFalse());
	}

	private void testAuthorization(String expectedUri,
			RequestMatcher authorizationMatcher) {
		ProjectRequest request = createProjectRequest();
		request.setGroupId("com.example.foo");
		request.setArtifactId("my-project");
		this.mockServer.expect(requestTo(expectedUri)).andExpect(method(HttpMethod.POST))
				.andExpect(authorizationMatcher)
				.andRespond(withStatus(HttpStatus.CREATED)
						.body(mockResponse(UUID.randomUUID().toString(), true))
						.contentType(MediaType.APPLICATION_JSON));
		this.statPublisher.handleEvent(new ProjectGeneratedEvent(request));
		this.mockServer.verify();
	}

	@Test
	void publishDocument() {
		ProjectRequest request = createProjectRequest();
		request.setGroupId("com.example.foo");
		request.setArtifactId("my-project");

		this.mockServer.expect(requestTo("http://example.com/elastic/initializr/request"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.groupId").value("com.example.foo"))
				.andExpect(jsonPath("$.artifactId").value("my-project"))
				.andRespond(withStatus(HttpStatus.CREATED)
						.body(mockResponse(UUID.randomUUID().toString(), true))
						.contentType(MediaType.APPLICATION_JSON));

		this.statPublisher.handleEvent(new ProjectGeneratedEvent(request));
		this.mockServer.verify();
	}

	@Test
	void recoverFromError() {
		ProjectRequest request = createProjectRequest();

		this.mockServer.expect(requestTo("http://example.com/elastic/initializr/request"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		this.mockServer.expect(requestTo("http://example.com/elastic/initializr/request"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		this.mockServer.expect(requestTo("http://example.com/elastic/initializr/request"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.CREATED)
						.body(mockResponse(UUID.randomUUID().toString(), true))
						.contentType(MediaType.APPLICATION_JSON));

		this.statPublisher.handleEvent(new ProjectGeneratedEvent(request));
		this.mockServer.verify();
	}

	@Test
	void fatalErrorOnlyLogs() {
		ProjectRequest request = createProjectRequest();
		this.retryTemplate.setRetryPolicy(new SimpleRetryPolicy(2,
				Collections.singletonMap(Exception.class, true)));

		this.mockServer.expect(requestTo("http://example.com/elastic/initializr/request"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		this.mockServer.expect(requestTo("http://example.com/elastic/initializr/request"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		this.statPublisher.handleEvent(new ProjectGeneratedEvent(request));
		this.mockServer.verify();
	}

	private static String mockResponse(String id, boolean created) {
		return "{\"_index\":\"initializr\",\"_type\":\"request\",\"_id\":\"" + id
				+ "\",\"_version\":1,\"_shards\""
				+ ":{\"total\":1,\"successful\":1,\"failed\":0},\"created\":" + created
				+ "}";
	}

	private static StatsProperties createProperties() {
		StatsProperties properties = new StatsProperties();
		Elastic elastic = properties.getElastic();
		elastic.setUri("http://example.com/elastic");
		return properties;
	}

}
