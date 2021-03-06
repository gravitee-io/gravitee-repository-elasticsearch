/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.repository.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.common.http.HttpHeaders;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.http.MediaType;
import io.gravitee.repository.elasticsearch.configuration.ElasticConfiguration;
import io.gravitee.repository.elasticsearch.configuration.Endpoint;
import io.gravitee.repository.elasticsearch.model.elasticsearch.ESSearchResponse;
import io.gravitee.repository.elasticsearch.model.elasticsearch.Health;
import io.gravitee.repository.elasticsearch.utils.FreeMarkerComponent;
import io.gravitee.repository.exceptions.TechnicalException;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.rxjava.core.Vertx;
import io.vertx.rxjava.core.buffer.Buffer;
import io.vertx.rxjava.core.http.HttpClient;
import io.vertx.rxjava.core.http.HttpClientRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import rx.Observable;
import rx.Subscriber;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Utility Elasticsearch Spring bean used to call Elasticsearch using the REST api.
 *
 * @author Guillaume Waignier
 * @author Sebastien Devaux
 */
public class ElasticsearchComponent {

	/** Logger. */
	private final Logger logger = LoggerFactory.getLogger(ElasticsearchComponent.class);

	//ES PATH
	private static final String URL_STATE_CLUSTER = "/_cluster/health";
	private static final String URL_SEARCH = "/_search?ignore_unavailable=true";
	private static final String URL_TEMPLATE = "/_template";
	private static final String URL_BULK = "/_bulk";

	private static final String CONTENT_TYPE = MediaType.APPLICATION_JSON + ";charset=UTF-8";

	private static final String HTTPS_SCHEME = "https";

	/**
	 * Configuration of Elasticsearch (cluster name, addresses, ...)
	 */
	@Autowired
	private ElasticConfiguration configuration;

	/**
	 * Templating tool.
	 */
	@Autowired
	private FreeMarkerComponent freeMarkerComponent;

	@Autowired
	private Vertx vertx;

	/**
	 * HTTP client.
	 */
	private HttpClient httpClient;

	/**
	 * JSON mapper.
	 */
	private ObjectMapper mapper;

	/**
	 * Authorization header if Elasticsearch is protected.
	 */
	private String authorizationHeader;

	private int majorVersion;

	/**
	 * Initialize the Async REST client.
	 */
	@PostConstruct
	public void start() throws ExecutionException, InterruptedException, IOException, TechnicalException {
		if (! configuration.getEndpoints().isEmpty()) {
			final Endpoint endpoint = configuration.getEndpoints().get(0);
			final URI elasticEdpt = URI.create(endpoint.getUrl());

			HttpClientOptions options = new HttpClientOptions()
					.setDefaultHost(elasticEdpt.getHost())
					.setDefaultPort(elasticEdpt.getPort() != -1 ? elasticEdpt.getPort() :
							(HTTPS_SCHEME.equals(elasticEdpt.getScheme()) ? 443 : 80));

			if (HTTPS_SCHEME.equals(elasticEdpt.getScheme())) {
				options
						.setSsl(true)
						.setTrustAll(true);
			}

			this.httpClient = vertx.createHttpClient(options);

			this.mapper = new ObjectMapper();

			// Use the ElasticConfiguration class to define username and password for ES
			// For example if Elasticsearch is protected by nginx or x-pack
			if (this.configuration.getUsername() != null) {
				this.authorizationHeader = this.initEncodedAuthorization(this.configuration.getUsername(), this.configuration.getPassword());
			}

			try {
				this.majorVersion = getMajorVersion();

				this.ensureTemplate();
			} catch (Exception ex) {
				logger.error("An error occurs while getting information from Elasticsearch at "
						+ elasticEdpt.toString(), ex);
			}
		}
	}

	private int getMajorVersion() throws ExecutionException, InterruptedException, IOException, TechnicalException {
		Observable<String> observable = Observable.unsafeCreate(subscriber -> {
			HttpClientRequest req = httpClient.get("/");
			addCommonHeaders(req);
			req
					.exceptionHandler(subscriber::onError)
					.toObservable()
					.flatMap(resp -> {
						if (resp.statusCode() != HttpStatusCode.OK_200) {
							subscriber.onError(new RuntimeException(
									"Impossible to call Elasticsearch. Elasticsearch response code is " + resp.statusCode()));
						}
						return Observable.just(Buffer.buffer()).mergeWith(resp.toObservable());
					})
					.reduce(Buffer::appendBuffer)
					.map(Buffer::toString)
					.subscribe(subscriber);

			req.end();
		});

		String body = observable.toBlocking().first();
		String version = mapper.readTree(body).path("version").path("number").asText();
		float result = Float.valueOf(version.substring(0, 3));
		int major = Integer.valueOf(version.substring(0, 1));
		if (result < 2) {
			logger.warn("Please upgrade to Elasticsearch 2 or later. version={}", version);
		}

		return major;
	}

	/**
	 * Create the Basic HTTP auth
	 * @param username username
	 * @param password password
	 * @return Basic auth string
	 */
	private String initEncodedAuthorization(final String username, final String password) {
		final String auth = username + ":" + password;
		final String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
		return "Basic " + encodedAuth;
	}

	/**
	 * Get the cluster health
	 * @return the cluster health
	 * @throws TechnicalException error occurs during ES call
	 */
	public Health getClusterHealth() throws TechnicalException {
		try {
			Observable<String> observable = Observable.unsafeCreate(subscriber -> {
				HttpClientRequest req = httpClient.get(URL_STATE_CLUSTER);
				addCommonHeaders(req);
				req
						.exceptionHandler(subscriber::onError)
						.toObservable()
						.flatMap(resp -> {
							if (resp.statusCode() != HttpStatusCode.OK_200) {
								subscriber.onError(new RuntimeException(
										"Impossible to call Elasticsearch. Elasticsearch response code is " + resp.statusCode()));
							}
							return Observable.just(Buffer.buffer()).mergeWith(resp.toObservable());
						})
						.reduce(Buffer::appendBuffer)
						.map(Buffer::toString)
						.subscribe(subscriber);

				req.end();
			});

			String body = observable.toBlocking().first();
			logger.debug("Response of ES for GET {} : {}", URL_STATE_CLUSTER, body);

			return this.mapper.readValue(body, Health.class);
		} catch (IOException e) {
			logger.error("Impossible to call Elasticsearch GET {}.", URL_STATE_CLUSTER, e);
			throw new TechnicalException("Impossible to call Elasticsearch. Error is " + e.getClass().getSimpleName(),
					e);
		}
	}

	/**
	 * Perform an HTTP search query
	 * @param indexes indexes names
	 * @param query json body query
	 * @return elasticsearch response
	 * @throws TechnicalException when a problem occur during the http call
	 */
	public ESSearchResponse search(final String indexes, final String query) throws TechnicalException {
		return this.search(indexes, null, query);
	}

	/**
	 * Perform an HTTP search query
	 * @param indexes indexes names. If null search on all indexes
	 * @param types elasticsearch document type separated by comma. If null search on all types
	 * @param query json body query
	 * @return elasticsearch response
	 * @throws TechnicalException when a problem occur during the http call
	 */
	public ESSearchResponse search(final String indexes, final String types, final String query) throws TechnicalException {
		try {
			// index can be null _search on all index
			final StringBuilder url = new StringBuilder()
					.append('/')
					.append(indexes);

			if (types != null) {
				url.append('/').append(types);
			}

			url.append(URL_SEARCH);

			final String queryUrl = url.toString();

			Observable<String> searchResponse = Observable.create(subscriber -> {
				HttpClientRequest req = httpClient
						.post(queryUrl)
						.putHeader(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE);

				addCommonHeaders(req);

				req
						.exceptionHandler(subscriber::onError)
						.toObservable()
						.onErrorReturn(throwable -> {
							logger.error("Unexpected error while calling Elasticsearch.", throwable);
							return null;
						})
						.flatMap(response -> {
							if (response == null || response.statusCode() != 200) {
								return Observable.just(null);
							} else {
								return Observable
										.just(Buffer.buffer())
										.mergeWith(response.toObservable())
										.reduce(Buffer::appendBuffer)
										.map(Buffer::toString);
							}
						})
						.subscribe(subscriber);

				req.end(query);
			});

			String body = searchResponse.toBlocking().single();
			if (body != null) {
				return mapper.readValue(body, ESSearchResponse.class);
			} else {
				throw new TechnicalException("Invalid search response from Elasticsearch.");
			}
		} catch (final Exception e) {
			logger.error("Impossible to call Elasticsearch", e);
			throw new TechnicalException("Impossible to call Elasticsearch.", e);
		}
	}

	/**
	 * Put the ES template.
	 *
	 * @throws TechnicalException
	 *             when a problem occur during the http call
	 */
	public void ensureTemplate() throws TechnicalException {
		try {
			String templateUrl = URL_TEMPLATE + "/gravitee";

			final Map<String, Object> data = new HashMap<>();
			data.put("indexName", this.configuration.getIndexName());
			data.put("numberOfShards", this.configuration.getNumberOfShards());
			data.put("numberOfReplicas", this.configuration.getNumberOfReplicas());
			final String template = this.freeMarkerComponent.generateFromTemplate("index-template-es-" + this.majorVersion + "x.ftl", data);

			logger.debug("PUT template : {}", template);

			Observable<String> observable = Observable.unsafeCreate(subscriber -> {
				HttpClientRequest req = httpClient
						.put(templateUrl)
						.putHeader(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE);
				addCommonHeaders(req);
				req
						.exceptionHandler(subscriber::onError)
						.toObservable()
						.flatMap(resp -> {
							if (resp.statusCode() != HttpStatusCode.OK_200) {
								subscriber.onError(new RuntimeException(
										"Impossible to call Elasticsearch PUT {}: {}" + templateUrl + resp.statusCode()));
							}
							return Observable.just(Buffer.buffer()).mergeWith(resp.toObservable());
						})
						.reduce(Buffer::appendBuffer)
						.map(Buffer::toString)
						.subscribe(subscriber);

				req.end(template);
			});

			String body = observable.toBlocking().first();

			logger.debug("Response of ES for PUT {} : {}",  URL_TEMPLATE + "/gravitee", body);
		} catch (final Exception e) {
			logger.error("Impossible to call Elasticsearch", e);
			throw new TechnicalException("Impossible to call Elasticsearch.", e);
		}
	}

	public void index(final String bulk) {
		try {
			logger.debug("Try to call POST {}, with body {}", URL_BULK, bulk);

			HttpClientRequest req = httpClient
					.post(URL_BULK)
					.putHeader(HttpHeaders.CONTENT_TYPE, "application/x-ndjson");
			addCommonHeaders(req);

			req
					.toObservable()
					.flatMap(resp -> {
						if (resp.statusCode() != HttpStatusCode.OK_200) {
							new RuntimeException(
									"Impossible to call Elasticsearch PUT {}: {}" + URL_BULK + resp.statusCode());
						}
						return Observable.just(Buffer.buffer()).mergeWith(resp.toObservable());
					})
					.reduce(Buffer::appendBuffer)
					.map(Buffer::toString)
					.subscribe(new Subscriber<String>() {
						@Override
						public void onCompleted() {

						}

						@Override
						public void onError(Throwable t) {
							logger.error("An error occurs while calling Elasticsearch POST {}", URL_BULK, t);
						}

						@Override
						public void onNext(String response) {
							logger.debug("Response of ES for POST {} : {}", URL_BULK, response);
						}
					});

			req.end(bulk);
		} catch (Exception ex) {
			logger.error("Unexpected error while bulk indexing data to Elasticsearch", ex);
		}
	}

	/**
	 * Add the common header to call Elasticsearch.
	 *
	 * @param request
	 *            the HTTP Client request
	 * @return HTTP Client request
	 */
	private void addCommonHeaders(final HttpClientRequest request) {
		request
				.putHeader(HttpHeaders.ACCEPT, CONTENT_TYPE)
				.putHeader(HttpHeaders.ACCEPT_CHARSET, StandardCharsets.UTF_8.name());

		// basic auth
		if (this.authorizationHeader != null) {
			request.putHeader(HttpHeaders.AUTHORIZATION, this.authorizationHeader);
		}
	}
}