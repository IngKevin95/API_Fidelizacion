package com.loyalty.e2e.support;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

public final class ApiClient {

    private static final String GATEWAY_BASE_URL = "http://localhost:8080";

    private ApiClient() {
    }

    public static RequestSpecification gateway() {
        return RestAssured.given().baseUri(GATEWAY_BASE_URL).contentType("application/json");
    }
}
