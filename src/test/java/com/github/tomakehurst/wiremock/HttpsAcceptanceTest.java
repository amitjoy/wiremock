/*
 * Copyright (C) 2011 Thomas Akehurst
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.tomakehurst.wiremock;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.http.HttpClientFactory;
import com.google.common.io.Resources;
import org.apache.http.HttpResponse;
import org.apache.http.MalformedChunkCodingException;
import org.apache.http.NoHttpResponseException;
import org.apache.http.ProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.*;
import static junit.framework.Assert.assertTrue;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public class HttpsAcceptanceTest {

    private static final int HTTPS_PORT = 8443;

    private WireMockServer wireMockServer;
    private HttpClient httpClient;

    private void startServerEnforcingClientCert(String keystorePath, String truststorePath) {
        WireMockConfiguration config = wireMockConfig().httpsPort(HTTPS_PORT);
        if (keystorePath != null) {
            config.keystorePath(keystorePath);
        }
        if (truststorePath != null) {
            config.truststorePath(truststorePath);
            config.needClientAuth(true);
        }
        config.bindAddress("localhost");

        wireMockServer = new WireMockServer(config);
        wireMockServer.start();
        WireMock.configure();

        httpClient = HttpClientFactory.createClient();
    }

    private void startServerWithKeystore(String keystorePath) {
        WireMockConfiguration config = wireMockConfig().httpsPort(HTTPS_PORT);
        if (keystorePath != null) {
            config.keystorePath(keystorePath);
        }

        wireMockServer = new WireMockServer(config);
        wireMockServer.start();
        WireMock.configure();

        httpClient = HttpClientFactory.createClient();
    }

    private void startServerWithDefaultKeystore() {
        startServerWithKeystore(null);
    }

    @After
    public void serverShutdown() {
        wireMockServer.stop();
    }

    @Test
    public void shouldReturnStubOnSpecifiedPort() throws Exception {
        startServerWithDefaultKeystore();
        stubFor(get(urlEqualTo("/https-test")).willReturn(aResponse().withStatus(200).withBody("HTTPS content")));

        assertThat(contentFor(url("/https-test")), is("HTTPS content"));
    }

    @Test
    public void emptyResponseFault() {
        startServerWithDefaultKeystore();
        stubFor(get(urlEqualTo("/empty/response")).willReturn(
                aResponse()
                        .withFault(Fault.EMPTY_RESPONSE)));


        getAndAssertUnderlyingExceptionInstanceClass(url("/empty/response"), NoHttpResponseException.class);
    }

    @Test
    public void malformedResponseChunkFault() {
        startServerWithDefaultKeystore();
        stubFor(get(urlEqualTo("/malformed/response")).willReturn(
                aResponse()
                        .withFault(Fault.MALFORMED_RESPONSE_CHUNK)));

        getAndAssertUnderlyingExceptionInstanceClass(url("/malformed/response"), MalformedChunkCodingException.class);
    }

    @Test
    public void randomDataOnSocketFault() {
        startServerWithDefaultKeystore();
        stubFor(get(urlEqualTo("/random/data")).willReturn(
                aResponse()
                        .withFault(Fault.RANDOM_DATA_THEN_CLOSE)));

        getAndAssertUnderlyingExceptionInstanceClass(url("/random/data"), ProtocolException.class);
    }

    @Test(expected = Exception.class)
    public void throwsExceptionWhenBadAlternativeKeystore() {
        String testKeystorePath = Resources.getResource("bad-keystore").toString();
        startServerWithKeystore(testKeystorePath);
    }

    @Test
    public void acceptsAlternativeKeystore() throws Exception {
        String testKeystorePath = Resources.getResource("test-keystore").toString();
        startServerWithKeystore(testKeystorePath);
        stubFor(get(urlEqualTo("/https-test")).willReturn(aResponse().withStatus(200).withBody("HTTPS content")));

        assertThat(contentFor(url("/https-test")), is("HTTPS content"));
    }

    @Test(expected=SSLHandshakeException.class)
    public void rejectsWithoutClientCertificate() throws Exception {
        String testTrustStorePath = Resources.getResource("test-clientstore").toString();
        String testKeystorePath = Resources.getResource("test-keystore").toString();
        startServerEnforcingClientCert(testKeystorePath, testTrustStorePath);
        stubFor(get(urlEqualTo("/https-test")).willReturn(aResponse().withStatus(200).withBody("HTTPS content")));

        contentFor(url("/https-test")); // this lacks the required client certificate
    }

    @Test
    public void acceptWithClientCertificate() throws Exception {
        String testTrustStorePath = Resources.getResource("test-clientstore").toString();
        String testKeystorePath = Resources.getResource("test-keystore").toString();
        String testClientCertPath = Resources.getResource("test-clientstore").toString();

        startServerEnforcingClientCert(testKeystorePath, testTrustStorePath);
        stubFor(get(urlEqualTo("/https-test")).willReturn(aResponse().withStatus(200).withBody("HTTPS content")));

        assertThat(secureContentFor(url("/https-test"), testKeystorePath, testClientCertPath), is("HTTPS content"));
    }


    private String url(String path) {
        return String.format("https://localhost:%d%s", HTTPS_PORT, path);
    }

    private void getAndAssertUnderlyingExceptionInstanceClass(String url, Class<?> expectedClass) {
        boolean thrown = false;
        try {
            contentFor(url);
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                assertThat(e.getCause(), instanceOf(expectedClass));
            } else {
                assertThat(e, instanceOf(expectedClass));
            }

            thrown = true;
        }

        assertTrue("No exception was thrown", thrown);
    }

    private String contentFor(String url) throws Exception {
        HttpGet get = new HttpGet(url);
        HttpResponse response = httpClient.execute(get);
        String content = EntityUtils.toString(response.getEntity());
        return content;
    }

    static String secureContentFor(String url, String  clientKeyStore, String clientTrustStore) throws Exception {
        // This is a horrible hack to get around a bug in Apache HTTP client or the underlying SSLSocketImpl.
        // It appears to randomly omit the requested client certificate from the information sent to the server
        // about half the time on Fedora 20 Sun JDK 1.6 through 1.8.
        // This would not be an acceptable hack for production code, but for a test, it should work until the bug
        // can be resolved.  Expect a false failure once every few billion runs.
        // https://issues.apache.org/jira/browse/HTTPCLIENT-1585
        Map<Exception,Integer> whoops=new TreeMap<Exception, Integer>(new Comparator<Exception>() {

            @Override
            public int compare(Exception e1, Exception e2) {
                if (e1.getStackTrace()==null) {
                    if (e2.getStackTrace()==null) {
                        return 0;
                    } else {
                        return 1;
                    }
                }
                if (e2.getStackTrace()==null) {
                    return -1;
                }
                return e1.getStackTrace()[0].toString().compareTo(e2.getStackTrace()[0].toString());
            }
        });
        Exception minEe = null;
        int minEc = Integer.MAX_VALUE;
        try {
            for (int i = 0; i < 64; i++) {
                try {
                    return secureContentFor0(url, clientKeyStore, clientTrustStore);
                } catch (SSLHandshakeException e) {
                    int c = 0;
                    if (whoops.containsKey(e)) {
                        c = (int) whoops.get(e);
                    }
                    whoops.put(e, c + 1);
                }
            }
        } finally {
            for (Map.Entry<Exception, Integer> me : whoops.entrySet()) {
                System.err.println("The following exception happened " + me.getValue() + " times.");
                me.getKey().printStackTrace();
                if (minEc > me.getValue()) {
                    minEc = me.getValue();
                    minEe = me.getKey();
                }
            }
        }
        throw minEe;
    }

    static String secureContentFor0(String url, String clientKeyStore, String clientTrustStore) throws Exception {
        KeyStore trustStore = readKeyStore(clientTrustStore);
        KeyStore keyStore = readKeyStore(clientKeyStore);

        // Trust own CA and all self-signed certs
        SSLContext sslcontext = SSLContexts.custom()
                .loadTrustMaterial(trustStore, new TrustSelfSignedStrategy())
                .loadKeyMaterial(keyStore, "password".toCharArray())
                .loadKeyMaterial(trustStore, "password".toCharArray())
                .useTLS()
                .build();

        // Allow TLSv1 protocol only
        SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(
                sslcontext,
                new String[] { "TLSv1" }, // supported protocols
                null,  // supported cipher suites
                SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

        CloseableHttpClient httpClient = HttpClients.custom()
                .setSSLSocketFactory(sslsf)
                .build();

        HttpGet get = new HttpGet(url);
        HttpResponse response = httpClient.execute(get);
        String content = EntityUtils.toString(response.getEntity());
        return content;
    }

    static KeyStore readKeyStore(String resourceURL) throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
        KeyStore trustStore  = KeyStore.getInstance(KeyStore.getDefaultType());
        FileInputStream instream = new FileInputStream(new URL(resourceURL).getFile());
        try {
            trustStore.load(instream, "password".toCharArray());
        } finally {
            instream.close();
        }
        return trustStore;
    }
}
