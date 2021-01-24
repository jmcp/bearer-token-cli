/*
 * Copyright (c) 2020, James C. McPherson. All rights reserved.
 *
 * This software is licensed under the Apache License 2.0
 */

/*
 * This file is an exploration of using Java to retrieve an OAuth2 access
 * token, and print a shell variable setting that can be used in your
 * environment with curl or wget.
 *
 * I don't think there are any best practices here, sorry.
 */

package com.liger_software.bearer_token_cli;

import com.fasterxml.jackson.jr.ob.JSON;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Command(name = "bearerTokenCLI", version = "1.0.0-SNAPSHOT",
        description="Retrieve a Bearer Token for authentication to an API",
        mixinStandardHelpOptions = true
)
class bearerTokenCLI implements Callable<Integer> {

/*
     @Option(names = {'-f', '--file'}, description = "Config filename to read")
     private String cfgFile = ".bearer.cfg";
 */

    @Option(names = {"-i", "--id"}, description = "Client Id",
            required = true)
    private String clientId;

    @Option(names = {"-s", "--secret"},
            description = "Client Secret (prompted interactively)",
            interactive = true, required = true)
    private String clientSecret;

    @Option(names = {"-a", "--auth"},
            description = "Authorization Server URL (without arguments)",
            required = true)
    private String authUrl;

    private static URI authServerURL;
    private static HttpResponse<String> response;

    final Logger logger = LoggerFactory.getLogger(bearerTokenCLI.class);

    /* returns true if we constructed the URL correctly, false otherwise */
    private boolean constructURL() {
        /*
         * We're taking this approach because the java.net.URI object is
         * immutable, unlike the equivalent in Python's requests module.
         * I find it dissatisfying, but taking this approach below lets me
         * minimise external dependencies.
         */
        URI protoURI;
        try {
            protoURI = new URI(authUrl);
        } catch (URISyntaxException urise) {
            logger.error("Unable to create a new URI from supplied " +
                    "authorization server url\n{}", authUrl);
            logger.error(urise.getReason());
            logger.error(urise.getMessage());
            return false;
        }

        try {
            authServerURL = new URI(protoURI.getScheme(), null,
                    protoURI.getHost(), protoURI.getPort(),
                    protoURI.getPath(),
                    "grant_type=client_credentials" +
                            "&client_id=" + clientId +
                            "&client_secret=" + clientSecret, null);
        } catch (URISyntaxException exc) {
            logger.warn("Received URISyntaxException");
            logger.warn(exc.getReason());
            logger.warn(exc.getMessage());
            logger.warn(exc.getInput());
            return false;
        }
        return true;
    }

    /* returns the token on success, null otherwise */
    private String sendRequest() {

         logger.info("Requesting " + authServerURL.toString());

         HttpRequest request = HttpRequest.newBuilder(authServerURL).build();
         HttpClient client = HttpClient.newHttpClient();

         try {
             response = client.send(request, BodyHandlers.ofString());
         } catch (java.io.IOException | java.lang.InterruptedException jiie) {
             /*
              * Note that this catch() uses Java7++ syntax for handling
              * multiple exceptions in the same block
              */
             logger.error("Received java.io.IOException");
             logger.error(jiie.getMessage());
             logger.error(jiie.getStackTrace().toString());
             return null;
         }

         if (response.statusCode() != 200) {
             /*
              * Something went wrong so print the url we requested, status code,
              * an error message and the response body as text.
              */
             logger.warn("Request was unsuccessful. Received status code " +
                     response.statusCode());
             logger.warn("URL requested was\n" + authServerURL.toString());
             logger.warn("Response body text:\n" + response.body());
             return null;
         }

         /*
          * Check that we've got "application/json" as the Content-Type.
          * Per https://tools.ietf.org/html/rfc7231#section-3.1 we know that
          * Content-Type is a semicolon-delimited string of
          *     type/subtype;charset;...
          * More importantly, we know from https://tools.ietf.org/html/rfc6749#section-5.1
          * that the response type MUST be JSON.
          */
         List<String> contentTypeHeader = response.headers().map().get("Content-Type");
         if (contentTypeHeader.isEmpty()) {
             logger.error("ERROR: Content-Type header is empty!");
             logger.error(response.headers().toString());
             return null;
         }

         String contentType = contentTypeHeader.get(0).split(";")[0];
         if (!contentType.equalsIgnoreCase("application/json")) {
             /* Not JSON! */
             logger.warn("Content-Type is {} not application/json.",
                     contentType);
             return null;
         }

         /*
          * Request was successful, so it's time to parse the response body
          * as JSON and extract the 'access_token' field.
          */
        String accessToken;
        try {
             Map<String, Object> containerJSON = JSON.std.mapFrom(response.body());
             accessToken = containerJSON.get("access_token").toString();
         } catch (IOException exc) {
             logger.warn("Caught exception {} while searching for access token",
                     exc.toString());
             logger.warn("Message:\n" + exc.getMessage());
             return null;
         }

         return accessToken;
     }

    @Override
    public Integer call() throws Exception {

        if (!constructURL()) {
            return CommandLine.ExitCode.SOFTWARE;
        }

        final String bearerToken = sendRequest();
        if (bearerToken == null) {
            return CommandLine.ExitCode.SOFTWARE;
        }

        System.out.println("export BEARER=\"Bearer " + bearerToken + "\"");
        return CommandLine.ExitCode.OK;
    }

    public static void main(String... args) throws Exception {
            int exitCode = new CommandLine(new bearerTokenCLI()).execute(args);
            System.exit(exitCode);
    }

}