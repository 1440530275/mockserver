package org.mockserver.netty;

import com.google.common.collect.ImmutableSet;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.InjectMocks;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.TimeService;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.model.RetrieveType;
import org.mockserver.netty.responsewriter.NettyResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.HttpRequestSerializer;
import org.mockserver.serialization.PortBindingSerializer;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.log.model.LogEntry.LOG_DATE_FORMAT;
import static org.mockserver.log.model.LogEntry.LogMessageType.*;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.netty.HttpRequestHandler.LOCAL_HOST_HEADERS;
import static org.mockserver.netty.HttpRequestHandler.PROXYING;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.PortBinding.portBinding;

/**
 * @author jamesdbloom
 */
public class MockServerHandlerTest {

    @Rule
    public ExpectedException exception = ExpectedException.none();
    private HttpState httpStateHandler;
    protected LifeCycle server;
    private HttpActionHandler mockActionHandler;
    private EmbeddedChannel embeddedChannel;
    @InjectMocks
    private HttpRequestHandler mockServerHandler;
    private final HttpRequestSerializer httpRequestSerializer = new HttpRequestSerializer(new MockServerLogger());
    private final ExpectationSerializer expectationSerializer = new ExpectationSerializer(new MockServerLogger());
    private final PortBindingSerializer portBindingSerializer = new PortBindingSerializer(new MockServerLogger());

    @BeforeClass
    public static void fixTime() {
        TimeService.fixedTime = true;
    }

    @Before
    public void setupFixture() {
        server = mock(MockServer.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        mockActionHandler = mock(HttpActionHandler.class);

        httpStateHandler = new HttpState(new MockServerLogger(), mock(Scheduler.class));
        mockServerHandler = new HttpRequestHandler(server, httpStateHandler, null);

        initMocks(this);

        embeddedChannel = new EmbeddedChannel(mockServerHandler);
    }

    @Test
    public void shouldRetrieveRequests() {
        // given
        httpStateHandler.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(RECEIVED_REQUEST)
        );

        // when
        HttpRequest expectationRetrieveRequestsRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withBody(
                httpRequestSerializer.serialize(request("request_one"))
            );
        embeddedChannel.writeInbound(expectationRetrieveRequestsRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), is(httpRequestSerializer.serialize(Collections.singletonList(
            request("request_one")
        ))));
    }

    @Test
    public void shouldClear() {
        // given
        httpStateHandler.add(new Expectation(request("request_one")).thenRespond(response("response_one")));
        httpStateHandler.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(EXPECTATION_MATCHED)
        );
        HttpRequest clearRequest = request("/mockserver/clear")
            .withMethod("PUT")
            .withBody(
                httpRequestSerializer.serialize(request("request_one"))
            );

        // when
        embeddedChannel.writeInbound(clearRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), is(""));
        assertThat(httpStateHandler.firstMatchingExpectation(request("request_one")), is(nullValue()));
        assertThat(httpStateHandler.retrieve(request("/mockserver/retrieve")
            .withMethod("PUT")
            .withBody(
                httpRequestSerializer.serialize(request("request_one"))
            )), is(response().withBody("[]", MediaType.JSON_UTF_8).withStatusCode(200)));
    }

    @Test
    public void shouldReturnStatus() {
        // given
        when(server.getLocalPorts()).thenReturn(Arrays.asList(1080, 1090));
        HttpRequest statusRequest = request("/mockserver/status").withMethod("PUT");

        // when
        embeddedChannel.writeInbound(statusRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), is(portBindingSerializer.serialize(
            portBinding(1080, 1090)
        )));
    }

    @Test
    public void shouldReturnStatusOnCustomPath() {
        String originalStatusPath = ConfigurationProperties.livenessHttpGetPath();
        try {
            // given
            ConfigurationProperties.livenessHttpGetPath("/livenessProbe");
            when(server.getLocalPorts()).thenReturn(Arrays.asList(1080, 1090));
            HttpRequest statusRequest = request("/livenessProbe").withMethod("GET");

            // when
            embeddedChannel.writeInbound(statusRequest);

            // then
            HttpResponse httpResponse = embeddedChannel.readOutbound();
            assertThat(httpResponse.getStatusCode(), is(200));
            assertThat(httpResponse.getBodyAsString(), is(portBindingSerializer.serialize(
                portBinding(1080, 1090)
            )));
        } finally {
            ConfigurationProperties.livenessHttpGetPath(originalStatusPath);
        }
    }

    @Test
    public void shouldBindNewPorts() {
        // given
        when(server.bindServerPorts(anyListOf(Integer.class))).thenReturn(Arrays.asList(1080, 1090));
        HttpRequest statusRequest = request("/mockserver/bind")
            .withMethod("PUT")
            .withBody(portBindingSerializer.serialize(
                portBinding(1080, 1090)
            ));

        // when
        embeddedChannel.writeInbound(statusRequest);

        // then
        verify(server).bindServerPorts(Arrays.asList(1080, 1090));
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), is(portBindingSerializer.serialize(
            portBinding(1080, 1090)
        )));
    }

    @Test
    public void shouldStop() throws InterruptedException {
        // given
        HttpRequest statusRequest = request("/mockserver/stop")
            .withMethod("PUT");

        // when
        embeddedChannel.writeInbound(statusRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), is((String) null));
        TimeUnit.SECONDS.sleep(1); // ensure stop thread has run
        verify(server).stop();
    }

    @Test
    public void shouldRetrieveRecordedExpectations() {
        // given
        httpStateHandler.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("request_one"))
                .setHttpResponse(response("response_one"))
                .setExpectation(new Expectation(request("request_one"), Times.once(), TimeToLive.unlimited(), 0).withId("key_one").thenRespond(response("response_one")))
        );

        // when
        HttpRequest expectationRetrieveExpectationsRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.RECORDED_EXPECTATIONS.name())
            .withBody(
                httpRequestSerializer.serialize(request("request_one"))
            );
        embeddedChannel.writeInbound(expectationRetrieveExpectationsRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), is(expectationSerializer.serialize(Collections.singletonList(
            new Expectation(request("request_one"), Times.once(), TimeToLive.unlimited(), 0).withId("key_one").thenRespond(response("response_one"))
        ))));
    }

    @Test
    public void shouldRetrieveLogMessages() {
        Level originalLevel = ConfigurationProperties.logLevel();
        try {
            // given
            ConfigurationProperties.logLevel("INFO");
            httpStateHandler.add(new Expectation(request("request_one")).withId("key_one").thenRespond(response("response_one")));

            // when
            HttpRequest retrieveLogRequest = request("/mockserver/retrieve")
                .withMethod("PUT")
                .withQueryStringParameter("type", RetrieveType.LOGS.name())
                .withBody(
                    httpRequestSerializer.serialize(request("request_one"))
                );
            embeddedChannel.writeInbound(retrieveLogRequest);

            // then
            HttpResponse response = embeddedChannel.readOutbound();
            assertThat(response.getStatusCode(), is(200));
            assertThat(
                response.getBodyAsString(),
                is(endsWith(LOG_DATE_FORMAT.format(new Date(TimeService.currentTimeMillis())) + " - creating expectation:" + NEW_LINE +
                    NEW_LINE +
                    "  {" + NEW_LINE +
                    "    \"id\" : \"key_one\"," + NEW_LINE +
                    "    \"priority\" : 0," + NEW_LINE +
                    "    \"httpRequest\" : {" + NEW_LINE +
                    "      \"path\" : \"request_one\"" + NEW_LINE +
                    "    }," + NEW_LINE +
                    "    \"times\" : {" + NEW_LINE +
                    "      \"unlimited\" : true" + NEW_LINE +
                    "    }," + NEW_LINE +
                    "    \"timeToLive\" : {" + NEW_LINE +
                    "      \"unlimited\" : true" + NEW_LINE +
                    "    }," + NEW_LINE +
                    "    \"httpResponse\" : {" + NEW_LINE +
                    "      \"statusCode\" : 200," + NEW_LINE +
                    "      \"reasonPhrase\" : \"OK\"," + NEW_LINE +
                    "      \"body\" : \"response_one\"" + NEW_LINE +
                    "    }" + NEW_LINE +
                    "  }" + NEW_LINE +
                    NEW_LINE +
                    "------------------------------------" + NEW_LINE +
                    LOG_DATE_FORMAT.format(new Date(TimeService.currentTimeMillis())) + " - retrieving logs that match:" + NEW_LINE +
                    "" + NEW_LINE +
                    "  {" + NEW_LINE +
                    "    \"path\" : \"request_one\"" + NEW_LINE +
                    "  }" + NEW_LINE +
                    NEW_LINE))
            );
        } finally {
            ConfigurationProperties.logLevel(originalLevel.name());
        }
    }

    @Test
    public void shouldAddExpectation() {
        // given
        Expectation expectationOne = new Expectation(request("request_one")).thenRespond(response("response_one"));
        HttpRequest request = request("/mockserver/expectation").withMethod("PUT").withBody(
            expectationSerializer.serialize(expectationOne)
        );

        // when
        embeddedChannel.writeInbound(request);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(201));
        assertThat(httpResponse.getBodyAsString(), containsString("[ {" + NEW_LINE +
            "  \"id\" : \""));
        assertThat(httpResponse.getBodyAsString(), containsString("\"," + NEW_LINE +
            "  \"priority\" : 0," + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"path\" : \"request_one\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"httpResponse\" : {" + NEW_LINE +
            "    \"statusCode\" : 200," + NEW_LINE +
            "    \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "    \"body\" : \"response_one\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"times\" : {" + NEW_LINE +
            "    \"unlimited\" : true" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"timeToLive\" : {" + NEW_LINE +
            "    \"unlimited\" : true" + NEW_LINE +
            "  }" + NEW_LINE +
            "} ]"));
        assertThat(httpStateHandler.firstMatchingExpectation(request("request_one")), is(expectationOne));
    }

    @Test
    public void shouldRetrieveActiveExpectations() {
        // given
        Expectation expectationOne = new Expectation(request("request_one")).thenRespond(response("response_one"));
        httpStateHandler.add(expectationOne);
        HttpRequest expectationRetrieveExpectationsRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.ACTIVE_EXPECTATIONS.name())
            .withBody(
                httpRequestSerializer.serialize(request("request_one"))
            );

        // when
        embeddedChannel.writeInbound(expectationRetrieveExpectationsRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), is(expectationSerializer.serialize(Collections.singletonList(
            expectationOne
        ))));
    }

    @Test
    public void shouldProxyRequestsWhenProxying() {
        // given
        HttpRequest request = request("request_one");
        InetSocketAddress remoteAddress = new InetSocketAddress(1080);
        embeddedChannel.attr(LOCAL_HOST_HEADERS).set(ImmutableSet.of(
            "local_address:666",
            "localhost:666",
            "127.0.0.1:666"
        ));
        embeddedChannel.attr(PROXYING).set(true);
        embeddedChannel.attr(REMOTE_SOCKET).set(remoteAddress);

        // when
        embeddedChannel.writeInbound(request);

        // then
        verify(mockActionHandler).processAction(
            eq(request),
            any(NettyResponseWriter.class),
            any(ChannelHandlerContext.class),
            eq(ImmutableSet.of(
                "local_address:666",
                "localhost:666",
                "127.0.0.1:666"
            )),
            eq(true),
            eq(false));
    }

    @Test
    public void shouldProxyRequestsWhenNotProxying() {
        // given
        HttpRequest request = request("request_one");
        InetSocketAddress remoteAddress = new InetSocketAddress(1080);
        embeddedChannel.attr(LOCAL_HOST_HEADERS).set(ImmutableSet.of(
            "local_address:666",
            "localhost:666",
            "127.0.0.1:666"
        ));
        embeddedChannel.attr(PROXYING).set(false);
        embeddedChannel.attr(REMOTE_SOCKET).set(remoteAddress);

        // when
        embeddedChannel.writeInbound(request);

        // then
        verify(mockActionHandler).processAction(
            eq(request),
            any(NettyResponseWriter.class),
            any(ChannelHandlerContext.class),
            eq(ImmutableSet.of(
                "local_address:666",
                "localhost:666",
                "127.0.0.1:666"
            )),
            eq(false),
            eq(false));
    }
}
