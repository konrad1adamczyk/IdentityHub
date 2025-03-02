/*
 *  Copyright (c) 2022 Microsoft Corporation
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Microsoft Corporation - initial API and implementation
 *
 */

package org.eclipse.edc.identityhub.api;

import com.nimbusds.jwt.SignedJWT;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.eclipse.edc.identityhub.spi.model.Descriptor;
import org.eclipse.edc.identityhub.spi.model.MessageRequestObject;
import org.eclipse.edc.identityhub.spi.model.Record;
import org.eclipse.edc.identityhub.spi.model.RequestObject;
import org.eclipse.edc.junit.extensions.EdcExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.identityhub.junit.testfixtures.VerifiableCredentialTestUtil.buildSignedJwt;
import static org.eclipse.edc.identityhub.junit.testfixtures.VerifiableCredentialTestUtil.generateCredential;
import static org.eclipse.edc.identityhub.junit.testfixtures.VerifiableCredentialTestUtil.generateEcKey;
import static org.eclipse.edc.identityhub.spi.model.WebNodeInterfaceMethod.COLLECTIONS_QUERY;
import static org.eclipse.edc.identityhub.spi.model.WebNodeInterfaceMethod.COLLECTIONS_WRITE;
import static org.eclipse.edc.identityhub.spi.model.WebNodeInterfaceMethod.FEATURE_DETECTION_READ;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

@ExtendWith(EdcExtension.class)
abstract class IdentityHubApiTest {

    public static final String DATA_FORMAT = "application/vc+jwt";
    private static final String IDENTITY_HUB_PATH = "/identity-hub";
    private String apiBasePath;

    @BeforeEach
    void setUp(EdcExtension extension) {
        apiBasePath = configureApi(extension);
    }

    @Test
    void writeAndQueryObject() {
        // Arrange
        var issuer = "http://some.test.url";
        var subject = "http://some.test.url";
        var credential = generateCredential();
        var jwt = buildSignedJwt(credential, issuer, subject, generateEcKey());

        // Act
        collectionsWrite(jwt);
        var credentials = collectionsQuery();

        // Assert
        assertThat(credentials).usingRecursiveFieldByFieldElementComparator().containsExactly(jwt.serialize().getBytes(UTF_8));
    }

    @Test
    void detectFeatures() {
        baseRequest()
                .body(createRequestObject(FEATURE_DETECTION_READ.getName()))
                .post()
                .then()
                .statusCode(200)
                .body("replies", hasSize(1))
                .body("replies[0].entries", hasSize(1))
                .body("replies[0].entries[0].interfaces.collections['CollectionsQuery']", is(true))
                .body("replies[0].entries[0].interfaces.collections['CollectionsWrite']", is(true));
    }

    @Test
    void useUnsupportedMethod() {
        baseRequest()
                .body(createRequestObject("Not supported method"))
                .post()
                .then()
                .statusCode(200)
                .body("replies", hasSize(1))
                .body("replies[0].status.code", equalTo(501))
                .body("replies[0].status.detail", equalTo("The interface method is not implemented"));
    }

    @Test
    void writeMalformedMessage() {
        byte[] data = "invalid base64".getBytes(UTF_8);
        baseRequest()
                .body(createRequestObject(COLLECTIONS_WRITE.getName(), data, DATA_FORMAT))
                .post()
                .then()
                .statusCode(200)
                .body("replies", hasSize(1))
                .body("replies[0].status.code", equalTo(400))
                .body("replies[0].status.detail", equalTo("The message was malformed or improperly constructed"));
    }

    protected abstract String configureApi(EdcExtension extension);

    private RequestSpecification baseRequest() {
        return given()
                .baseUri(apiBasePath)
                .basePath(IDENTITY_HUB_PATH)
                .contentType(ContentType.JSON)
                .when();
    }

    private RequestObject createRequestObject(String method) {
        return createRequestObject(method, null);
    }

    private RequestObject createRequestObject(String method, byte[] data) {
        return createRequestObject(method, data, null);
    }

    private RequestObject createRequestObject(String method, byte[] data, String dataFormat) {
        return RequestObject.Builder.newInstance()
                .messages(List.of(
                        MessageRequestObject.Builder.newInstance()
                                .descriptor(Descriptor.Builder.newInstance()
                                        .method(method)
                                        .recordId(UUID.randomUUID().toString())
                                        .dataFormat(dataFormat)
                                        .dateCreated(Instant.now().getEpochSecond())
                                        .build())
                                .data(data)
                                .build()))
                .build();
    }

    private void collectionsWrite(SignedJWT verifiableCredential) {
        byte[] data = verifiableCredential.serialize().getBytes(UTF_8);
        baseRequest()
                .body(createRequestObject(COLLECTIONS_WRITE.getName(), data, DATA_FORMAT))
                .post()
                .then()
                .statusCode(200)
                .body("replies", hasSize(1))
                .body("replies[0].status.code", equalTo(200))
                .body("replies[0].status.detail", equalTo("The message was successfully processed"));
    }

    private List<byte[]> collectionsQuery() {
        return baseRequest()
                .body(createRequestObject(COLLECTIONS_QUERY.getName()))
                .post()
                .then()
                .statusCode(200)
                .body("replies", hasSize(1))
                .body("replies[0].status.code", equalTo(200))
                .body("replies[0].status.detail", equalTo("The message was successfully processed"))
                .extract().body().jsonPath().getList("replies[0].entries", Record.class)
                .stream().map(s -> s.getData())
                .collect(Collectors.toList());
    }
}
