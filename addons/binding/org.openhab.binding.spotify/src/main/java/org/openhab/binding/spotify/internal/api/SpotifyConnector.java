/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.spotify.internal.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.openhab.binding.spotify.internal.api.exception.SpotifyAuthorizationException;
import org.openhab.binding.spotify.internal.api.exception.SpotifyException;
import org.openhab.binding.spotify.internal.api.exception.SpotifyTokenExpiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Class to perform the actual call to the Spotify Api, interprets the returned Http status codes, and handles the error
 * codes returned by the Spotify Web Api.
 *
 * @author Hilbrand Bouwkamp - Initial contribution
 */
public class SpotifyConnector {

    // HTTP status codes returned by Spotify
    private static final int HTTP_OK = 200;
    private static final int HTTP_CREATED = 201;
    private static final int HTTP_ACCEPTED = 202;
    private static final int HTTP_NO_CONTENT = 204;
    private static final int HTTP_NOT_MODIFIED = 304;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_RATE_LIMIT_EXCEEDED = 429;
    private static final int HTTP_INTERNAL_SERVER_ERROR = 500;
    private static final int HTTP_BAD_GATEWAY = 502;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final int HTTP_CLIENT_TIMEOUT_SECONDS = 30;
    private static final int HTTP_CLIENT_RETRY_COUNT = 5;
    private static final int DEFAULT_RETRY_DELAY_SECONDS = 5;

    private final Logger logger = LoggerFactory.getLogger(SpotifyConnector.class);

    private final JsonParser parser = new JsonParser();
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;

    /**
     * Constructor.
     *
     * @param scheduler Scheduler to reschedule calls when rate limit exceeded or call not ready
     * @param httpClient http client to use to make http calls
     */
    public SpotifyConnector(ScheduledExecutorService scheduler, HttpClient httpClient) {
        this.scheduler = scheduler;
        this.httpClient = httpClient;
    }

    /**
     * Performs a call to the Spotify Web Api and returns the raw response. In there are problems this method can throw
     * a Spotify exception.
     *
     * @param requester The function to construct the request with http client that is passed as argument to the
     *            function
     * @param authorization The authorization string to use in the Authorization header
     * @return the raw reponse given
     */
    public ContentResponse request(Function<HttpClient, Request> requester, String authorization) {
        Caller caller = new Caller(requester, authorization);

        try {
            return caller.call().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpotifyException("Thread interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();

            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            } else {
                throw new SpotifyException(e.getMessage(), e);
            }
        }
    }

    /**
     * Class to handle a call to the Spotify Web Api. In case of rate limiting or not finished jobs it will retry in a
     * specified time frame. It reties a number of times and then gives up with an exception.
     *
     * @author Hilbrand Bouwkamp - Initial contribution
     */
    private class Caller {
        private final Function<HttpClient, Request> requester;
        private final String authorization;

        private CompletableFuture<ContentResponse> future = new CompletableFuture<>();
        private int delaySeconds;
        private int attempts;

        /**
         * Constructor.
         *
         * @param requester The function to construct the request with http client that is passed as argument to the
         *            function
         * @param authorization The authorization string to use in the Authorization header
         */
        public Caller(Function<HttpClient, Request> requester, String authorization) {
            this.requester = requester;
            this.authorization = authorization;
        }

        /**
         * Performs the request as a Future. It will set the Future state once it's finished. This method will be
         * scheduled again when the call is to be retried. The original caller should call the get method on the Future
         * to wait for the call to finish. The first try is not scheduled so if it succeeds on the first call the get
         * method directly returns the value.
         *
         * @return the Future holding the call
         */
        public CompletableFuture<ContentResponse> call() {
            attempts++;
            try {
                boolean success = processResponse(
                        requester.apply(httpClient).header(AUTHORIZATION_HEADER, authorization)
                                .timeout(HTTP_CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS).send());

                if (!success) {
                    if (attempts < HTTP_CLIENT_RETRY_COUNT) {
                        logger.debug("Spotify Web API call attempt: {}", attempts);

                        scheduler.schedule(this::call, delaySeconds, TimeUnit.SECONDS);
                    } else {
                        logger.debug("Giving up on accessing Spotify Web API. Check network connectivity!");
                        future.completeExceptionally(new SpotifyException(
                                "Could not reach the Spotify Web Api after " + attempts + " retries."));
                    }
                }
            } catch (ExecutionException e) {
                future.completeExceptionally(e.getCause());
            } catch (RuntimeException | TimeoutException e) {
                future.completeExceptionally(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
            }
            return future;
        }

        /**
         * Processes the response of the Spotify Web Api call and handles the http status codes. The method returns true
         * if the response indicates a successful and false if the call should be retried. If there were other problems
         * a Spotify exception is thrown indicating no retry should be done an the user should be informed.
         *
         * @param response the reponse given by the Spotify Web Api
         * @return true if the response indicated a successful call, false if the call should be retried
         */
        private boolean processResponse(ContentResponse response) {
            boolean success = false;

            logger.debug("Response Code: {}", response.getStatus());
            if (logger.isTraceEnabled()) {
                logger.trace("Response Data: {}", response.getContentAsString());
            }
            switch (response.getStatus()) {
                case HTTP_OK:
                case HTTP_CREATED:
                case HTTP_NO_CONTENT:
                case HTTP_NOT_MODIFIED:
                    future.complete(response);
                    success = true;
                    break;
                case HTTP_ACCEPTED:
                case HTTP_SERVICE_UNAVAILABLE:
                    logger.debug(
                            "Spotify Web API returned code 202 - The request has been accepted for processing, but the processing has not been completed. Retrying...");
                    delaySeconds = DEFAULT_RETRY_DELAY_SECONDS;
                    break;
                case HTTP_BAD_REQUEST:
                    throw new SpotifyException(processErrorState(response));
                case HTTP_UNAUTHORIZED:
                    throw new SpotifyAuthorizationException(processErrorState(response));
                case HTTP_RATE_LIMIT_EXCEEDED:
                    // Response Code 429 means requests rate limits exceeded.
                    String retryAfter = response.getHeaders().get("Retry-After");

                    logger.debug(
                            "Spotify Web API returned code 429 (rate limit exceeded). Retry After {} seconds. Decrease polling interval of bridge! Going to sleep...",
                            retryAfter);
                    delaySeconds = Integer.parseInt(retryAfter);
                    break;
                case HTTP_FORBIDDEN:
                    // Process for authorization error, and logging.
                    processErrorState(response);
                    future.complete(response);
                    success = true;
                    break;
                case HTTP_NOT_FOUND:
                    throw new SpotifyException(processErrorState(response));
                case HTTP_INTERNAL_SERVER_ERROR:
                case HTTP_BAD_GATEWAY:
                default:
                    throw new SpotifyException("Spotify returned with error status: " + response.getStatus());
            }
            return success;
        }

        /**
         * Processes the responded content if the status code indicated an error. If the response could be parsed the
         * content error message is returned. If the error indicated a token or authorization error a specific exception
         * is thrown. If an error message is thrown the caller throws the appropriate exception based on the state with
         * which the error was returned by the Spotify Web Api.
         *
         * @param response content returned by Spotify Web Api
         * @return the error messages
         */
        private String processErrorState(ContentResponse response) {
            try {
                JsonElement element = parser.parse(response.getContentAsString());

                if (element.isJsonObject()) {
                    JsonObject object = element.getAsJsonObject();
                    if (object.has("error") && object.get("error").isJsonObject()) {
                        String message = object.get("error").getAsJsonObject().get("message").getAsString();

                        logger.debug("Bad request: {}", message);
                        if (message.contains("expired")) {
                            throw new SpotifyTokenExpiredException(message);
                        } else {
                            return message;
                        }
                    } else if (object.has("error_description")) {
                        String errorDescription = object.get("error_description").getAsString();

                        logger.debug("Authorization error: {}", errorDescription);
                        throw new SpotifyAuthorizationException(errorDescription);
                    }
                }
                logger.debug("Unknown response: {}", response);
                return "Unknown response";
            } catch (JsonSyntaxException e) {
                logger.debug("Response was not json: ", e);
                return "Unknown response";
            }
        }
    }
}
