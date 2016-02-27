package com.github.ambry.frontend;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.clustermap.MockClusterMap;
import com.github.ambry.commons.ByteBufferReadableStreamChannel;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.messageformat.BlobInfo;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.rest.MockRestRequest;
import com.github.ambry.rest.MockRestResponseChannel;
import com.github.ambry.rest.ResponseStatus;
import com.github.ambry.rest.RestMethod;
import com.github.ambry.rest.RestRequest;
import com.github.ambry.rest.RestRequestMetricsTracker;
import com.github.ambry.rest.RestResponseChannel;
import com.github.ambry.rest.RestResponseHandler;
import com.github.ambry.rest.RestServiceErrorCode;
import com.github.ambry.rest.RestServiceException;
import com.github.ambry.rest.RestUtils;
import com.github.ambry.rest.RestUtilsTest;
import com.github.ambry.router.AsyncWritableChannel;
import com.github.ambry.router.Callback;
import com.github.ambry.router.CopyingAsyncWritableChannel;
import com.github.ambry.router.InMemoryRouter;
import com.github.ambry.router.ReadableStreamChannel;
import com.github.ambry.router.Router;
import com.github.ambry.router.RouterErrorCode;
import com.github.ambry.router.RouterException;
import com.github.ambry.utils.Utils;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;


/**
 * Unit tests for {@link AmbryBlobStorageService}.
 */
public class AmbryBlobStorageServiceTest {

  private static final ClusterMap CLUSTER_MAP;

  static {
    try {
      CLUSTER_MAP = new MockClusterMap();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private final InMemoryRouter router;
  private final FrontendTestResponseHandler responseHandler;
  private final AmbryBlobStorageService ambryBlobStorageService;

  /**
   * Sets up the {@link AmbryBlobStorageService} instance before a test.
   * @throws InstantiationException
   */
  public AmbryBlobStorageServiceTest()
      throws InstantiationException {
    RestRequestMetricsTracker.setDefaults(new MetricRegistry());
    router = new InMemoryRouter(new VerifiableProperties(new Properties()));
    responseHandler = new FrontendTestResponseHandler();
    ambryBlobStorageService = getAmbryBlobStorageService();
    responseHandler.start();
    ambryBlobStorageService.start();
  }

  /**
   * Shuts down the {@link AmbryBlobStorageService} instance after all tests.
   * @throws IOException
   */
  @After
  public void shutdownAmbryBlobStorageService()
      throws IOException {
    ambryBlobStorageService.shutdown();
    responseHandler.shutdown();
    router.close();
  }

  /**
   * Tests basic startup and shutdown functionality (no exceptions).
   * @throws InstantiationException
   * @throws IOException
   */
  @Test
  public void startShutDownTest()
      throws InstantiationException, IOException {
    ambryBlobStorageService.start();
    ambryBlobStorageService.shutdown();
  }

  /**
   * Tests for {@link AmbryBlobStorageService#shutdown()} when {@link AmbryBlobStorageService#start()} has not been
   * called previously.
   * <p/>
   * This test is for  cases where {@link AmbryBlobStorageService#start()} has failed and
   * {@link AmbryBlobStorageService#shutdown()} needs to be run.
   * @throws IOException
   */
  @Test
  public void shutdownWithoutStartTest()
      throws IOException {
    AmbryBlobStorageService ambryBlobStorageService = getAmbryBlobStorageService();
    ambryBlobStorageService.shutdown();
  }

  /**
   * This tests for exceptions thrown when an {@link AmbryBlobStorageService} instance is used without calling
   * {@link AmbryBlobStorageService#start()} first.
   * @throws Exception
   */
  @Test
  public void useServiceWithoutStartTest()
      throws Exception {
    // simulating by shutting down first.
    ambryBlobStorageService.shutdown();
    // fine to use without start.
    postGetHeadDeleteTest();
  }

  /**
   * Checks for reactions of all methods in {@link AmbryBlobStorageService} to null arguments.
   * @throws Exception
   */
  @Test
  public void nullInputsForFunctionsTest()
      throws Exception {
    doNullInputsForFunctionsTest("handleGet");
    doNullInputsForFunctionsTest("handlePost");
    doNullInputsForFunctionsTest("handleDelete");
    doNullInputsForFunctionsTest("handleHead");
  }

  /**
   * Checks reactions of all methods in {@link AmbryBlobStorageService} to a {@link Router} that throws
   * {@link RuntimeException}.
   * @throws Exception
   */
  @Test
  public void runtimeExceptionRouterTest()
      throws Exception {
    // set InMemoryRouter up to throw RuntimeException
    Properties properties = new Properties();
    properties.setProperty(InMemoryRouter.OPERATION_THROW_EARLY_RUNTIME_EXCEPTION, "true");
    router.setVerifiableProperties(new VerifiableProperties(properties));

    doRuntimeExceptionRouterTest(RestMethod.GET);
    doRuntimeExceptionRouterTest(RestMethod.POST);
    doRuntimeExceptionRouterTest(RestMethod.DELETE);
    doRuntimeExceptionRouterTest(RestMethod.HEAD);
  }

  /**
   * Checks reactions of all methods in {@link AmbryBlobStorageService} to bad {@link RestResponseHandler} and
   * {@link RestRequest} implementations.
   * @throws Exception
   */
  @Test
  public void badResponseHandlerAndRestRequestTest()
      throws Exception {
    RestRequest restRequest = new BadRestRequest();
    MockRestResponseChannel restResponseChannel = new MockRestResponseChannel();

    // What happens inside AmbryBlobStorageService during this test?
    // 1. Since the RestRequest throws errors, AmbryBlobStorageService will attempt to submit response with exception
    //      to FrontendTestResponseHandler.
    // 2. The submission will fail because FrontendTestResponseHandler has been shutdown.
    // 3. AmbryBlobStorageService will directly complete the request over the RestResponseChannel with the *original*
    //      exception.
    // 4. It will then try to release resources but closing the RestRequest will also throw an exception. This exception
    //      is swallowed.
    // What the test is looking for -> No exceptions thrown when the handle is run and the original exception arrives
    // safely.
    responseHandler.shutdown();

    ambryBlobStorageService.handleGet(restRequest, restResponseChannel);
    // IllegalStateException is thrown in BadRestRequest.
    assertEquals("Unexpected exception", IllegalStateException.class, restResponseChannel.getException().getClass());

    responseHandler.reset();
    restResponseChannel = new MockRestResponseChannel();
    ambryBlobStorageService.handlePost(restRequest, restResponseChannel);
    // IllegalStateException is thrown in BadRestRequest.
    assertEquals("Unexpected exception", IllegalStateException.class, restResponseChannel.getException().getClass());

    responseHandler.reset();
    restResponseChannel = new MockRestResponseChannel();
    ambryBlobStorageService.handleDelete(restRequest, restResponseChannel);
    // IllegalStateException is thrown in BadRestRequest.
    assertEquals("Unexpected exception", IllegalStateException.class, restResponseChannel.getException().getClass());

    responseHandler.reset();
    restResponseChannel = new MockRestResponseChannel();
    ambryBlobStorageService.handleHead(restRequest, restResponseChannel);
    // IllegalStateException is thrown in BadRestRequest.
    assertEquals("Unexpected exception", IllegalStateException.class, restResponseChannel.getException().getClass());
  }

  /**
   * Tests {@link AmbryBlobStorageService#submitResponse(RestRequest, RestResponseChannel, ReadableStreamChannel,
   * Exception)}.
   * @throws JSONException
   * @throws UnsupportedEncodingException
   * @throws URISyntaxException
   */
  @Test
  public void submitResponseTest()
      throws JSONException, UnsupportedEncodingException, URISyntaxException {
    String exceptionMsg = new String(getRandomBytes(10));
    responseHandler.shutdown();
    // handleResponse of FrontendTestResponseHandler throws exception because it has been shutdown.
    try {
      // there is an exception already.
      RestRequest restRequest = createRestRequest(RestMethod.GET, "/", null, null);
      assertTrue("RestRequest channel is not open", restRequest.isOpen());
      MockRestResponseChannel restResponseChannel = new MockRestResponseChannel();
      ambryBlobStorageService
          .submitResponse(restRequest, restResponseChannel, null, new RuntimeException(exceptionMsg));
      assertEquals("Unexpected exception message", exceptionMsg, restResponseChannel.getException().getMessage());

      // there is no exception and exception thrown when the response is submitted.
      restRequest = createRestRequest(RestMethod.GET, "/", null, null);
      assertTrue("RestRequest channel is not open", restRequest.isOpen());
      restResponseChannel = new MockRestResponseChannel();
      ReadableStreamChannel response = new ByteBufferReadableStreamChannel(ByteBuffer.allocate(0));
      assertTrue("Response channel is not open", response.isOpen());
      ambryBlobStorageService.submitResponse(restRequest, restResponseChannel, response, null);
      assertNotNull("There is no cause of failure", restResponseChannel.getException());
      // resources should have been cleaned up.
      assertFalse("Response channel is not cleaned up", response.isOpen());
    } finally {
      responseHandler.start();
    }
  }

  /**
   * Tests releasing of resources if response submission fails.
   * @throws JSONException
   * @throws UnsupportedEncodingException
   * @throws URISyntaxException
   */
  @Test
  public void releaseResourcesTest()
      throws JSONException, UnsupportedEncodingException, URISyntaxException {
    responseHandler.shutdown();
    // handleResponse of FrontendTestResponseHandler throws exception because it has been shutdown.
    try {
      RestRequest restRequest = createRestRequest(RestMethod.GET, "/", null, null);
      MockRestResponseChannel restResponseChannel = new MockRestResponseChannel();
      ReadableStreamChannel channel = new ByteBufferReadableStreamChannel(ByteBuffer.allocate(0));
      assertTrue("RestRequest channel not open", restRequest.isOpen());
      assertTrue("ReadableStreamChannel not open", channel.isOpen());
      ambryBlobStorageService.submitResponse(restRequest, restResponseChannel, channel, null);
      assertFalse("ReadableStreamChannel is still open", channel.isOpen());

      // null ReadableStreamChannel
      restRequest = createRestRequest(RestMethod.GET, "/", null, null);
      restResponseChannel = new MockRestResponseChannel();
      assertTrue("RestRequest channel not open", restRequest.isOpen());
      ambryBlobStorageService.submitResponse(restRequest, restResponseChannel, null, null);

      // bad RestRequest (close() throws IOException)
      channel = new ByteBufferReadableStreamChannel(ByteBuffer.allocate(0));
      restResponseChannel = new MockRestResponseChannel();
      assertTrue("ReadableStreamChannel not open", channel.isOpen());
      ambryBlobStorageService.submitResponse(new BadRestRequest(), restResponseChannel, channel, null);

      // bad ReadableStreamChannel (close() throws IOException)
      restRequest = createRestRequest(RestMethod.GET, "/", null, null);
      restResponseChannel = new MockRestResponseChannel();
      assertTrue("RestRequest channel not open", restRequest.isOpen());
      ambryBlobStorageService.submitResponse(restRequest, restResponseChannel, new BadRSC(), null);
    } finally {
      responseHandler.start();
    }
  }

  /**
   * Tests {@link AmbryBlobStorageService#getOperationOrBlobIdFromUri(RestRequest)}.
   * @throws JSONException
   * @throws UnsupportedEncodingException
   * @throws URISyntaxException
   */
  @Test
  public void getOperationOrBlobIdFromUriTest()
      throws JSONException, UnsupportedEncodingException, URISyntaxException {
    String path = "expectedPath";
    RestRequest restRequest = createRestRequest(RestMethod.GET, "/" + path, null, null);
    assertEquals("Unexpected path", path, AmbryBlobStorageService.getOperationOrBlobIdFromUri(restRequest));
  }

  /**
   * Tests blob POST, GET, HEAD and DELETE operations.
   * @throws Exception
   */
  @Test
  public void postGetHeadDeleteTest()
      throws Exception {
    final int CONTENT_LENGTH = 1024;
    ByteBuffer content = ByteBuffer.wrap(getRandomBytes(CONTENT_LENGTH));
    String serviceId = "postGetHeadDeleteServiceID";
    String contentType = "application/octet-stream";
    String ownerId = "postGetHeadDeleteOwnerID";
    JSONObject headers = new JSONObject();
    setAmbryHeaders(headers, CONTENT_LENGTH, 7200, false, serviceId, contentType, ownerId);
    Map<String, String> userMetadata = new HashMap<String, String>();
    userMetadata.put(RestUtils.Headers.UserMetaData_Header_Prefix + "key1", "value1");
    userMetadata.put(RestUtils.Headers.UserMetaData_Header_Prefix + "key2", "value2");
    RestUtilsTest.setUserMetadataHeaders(headers, userMetadata);
    String blobId = postBlobAndVerify(headers, content);
    getBlobAndVerify(blobId, headers, content);
    getHeadAndVerify(blobId, headers);
    deleteBlobAndVerify(blobId);
  }

  /**
   * Tests for non common case scenarios for {@link HeadForGetCallback}.
   * @throws Exception
   */
  @Test
  public void headForGetCallbackTest()
      throws Exception {
    String exceptionMsg = new String(getRandomBytes(10));
    responseHandler.reset();

    // the good case is tested through the postGetHeadDeleteTest() (result non-null, exception null)
    // Both arguments null
    RestRequest restRequest = createRestRequest(RestMethod.GET, "/", null, null);
    MockRestResponseChannel restResponseChannel = new MockRestResponseChannel();
    HeadForGetCallback callback =
        new HeadForGetCallback(ambryBlobStorageService, restRequest, restResponseChannel, router, 0);
    callback.onCompletion(null, null);
    // there should be an exception
    assertEquals("Both arguments null should have thrown exception", IllegalStateException.class,
        responseHandler.getException().getClass());
    // Nothing should be closed.
    assertTrue("RestRequest channel is not open", restRequest.isOpen());
    restRequest.close();

    // Exception is not null.
    responseHandler.reset();
    restRequest = createRestRequest(RestMethod.GET, "/", null, null);
    restResponseChannel = new MockRestResponseChannel();
    callback = new HeadForGetCallback(ambryBlobStorageService, restRequest, restResponseChannel, router, 0);
    callback.onCompletion(null, new RuntimeException(exceptionMsg));
    assertEquals("Unexpected exception message", exceptionMsg, responseHandler.getException().getMessage());
    // Nothing should be closed.
    assertTrue("RestRequest channel is not open", restRequest.isOpen());
    restRequest.close();

    // Exception is RouterException.
    responseHandler.reset();
    restRequest = createRestRequest(RestMethod.GET, "/", null, null);
    restResponseChannel = new MockRestResponseChannel();
    callback = new HeadForGetCallback(ambryBlobStorageService, restRequest, restResponseChannel, router, 0);
    callback.onCompletion(null, new RouterException(exceptionMsg, RouterErrorCode.UnexpectedInternalError));
    assertEquals("RouterException not converted to RestServiceException", RestServiceException.class,
        responseHandler.getException().getClass());
    if (!responseHandler.getException().getMessage().contains(exceptionMsg)) {
      fail("Exception msg [" + responseHandler.getException().getMessage() + "] does contain expected substring ["
          + exceptionMsg + "]");
    }
    // Nothing should be closed.
    assertTrue("RestRequest channel is not open", restRequest.isOpen());
    restRequest.close();

    // Callback encounters a processing error (induced here via a bad RestRequest).
    restRequest = new BadRestRequest();
    // there is an exception already.
    restResponseChannel = new MockRestResponseChannel();
    callback = new HeadForGetCallback(ambryBlobStorageService, restRequest, restResponseChannel, router, 0);
    callback.onCompletion(null, new RuntimeException(exceptionMsg));
    assertEquals("Unexpected exception message", exceptionMsg, restResponseChannel.getException().getMessage());

    // there is no exception and the exception thrown in the callback is the primary exception.
    restResponseChannel = new MockRestResponseChannel();
    callback = new HeadForGetCallback(ambryBlobStorageService, restRequest, restResponseChannel, router, 0);
    BlobInfo blobInfo = new BlobInfo(null, null);
    callback.onCompletion(blobInfo, null);
    assertNotNull("There is no cause of failure", restResponseChannel.getException());
  }

  /**
   * Tests for non common case scenarios for {@link GetCallback}.
   * @throws Exception
   */
  @Test
  public void getCallbackTest()
      throws Exception {
    String exceptionMsg = new String(getRandomBytes(10));
    responseHandler.reset();

    // the good case is tested through the postGetHeadDeleteTest() (result non-null, exception null)
    // Both arguments null
    RestRequest restRequest = createRestRequest(RestMethod.GET, "/", null, null);
    MockRestResponseChannel restResponseChannel = new MockRestResponseChannel();
    GetCallback callback = new GetCallback(ambryBlobStorageService, restRequest, restResponseChannel);
    callback.onCompletion(null, null);
    // there should be an exception
    assertEquals("Both arguments null should have thrown exception", IllegalStateException.class,
        responseHandler.getException().getClass());
    // Nothing should be closed.
    assertTrue("RestRequest channel is not open", restRequest.isOpen());
    restRequest.close();

    // Exception is not null.
    responseHandler.reset();
    restRequest = createRestRequest(RestMethod.GET, "/", null, null);
    restResponseChannel = new MockRestResponseChannel();
    callback = new GetCallback(ambryBlobStorageService, restRequest, restResponseChannel);
    callback.onCompletion(null, new RuntimeException(exceptionMsg));
    assertEquals("Unexpected exception message", exceptionMsg, responseHandler.getException().getMessage());
    // Nothing should be closed.
    assertTrue("RestRequest channel is not open", restRequest.isOpen());
    restRequest.close();

    // Exception is RouterException.
    responseHandler.reset();
    restRequest = createRestRequest(RestMethod.GET, "/", null, null);
    restResponseChannel = new MockRestResponseChannel();
    callback = new GetCallback(ambryBlobStorageService, restRequest, restResponseChannel);
    callback.onCompletion(null, new RouterException(exceptionMsg, RouterErrorCode.UnexpectedInternalError));
    assertEquals("RouterException not converted to RestServiceException", RestServiceException.class,
        responseHandler.getException().getClass());
    if (!responseHandler.getException().getMessage().contains(exceptionMsg)) {
      fail("Exception msg [" + responseHandler.getException().getMessage() + "] does contain expected substring ["
          + exceptionMsg + "]");
    }
    // Nothing should be closed.
    assertTrue("RestRequest channel is not open", restRequest.isOpen());
    restRequest.close();

    // Callback encounters a processing error (induced here via a bad RestRequest).
    restRequest = new BadRestRequest();
    // there is an exception already.
    restResponseChannel = new MockRestResponseChannel();
    callback = new GetCallback(ambryBlobStorageService, restRequest, restResponseChannel);
    callback.onCompletion(null, new RuntimeException(exceptionMsg));
    assertEquals("Unexpected exception message", exceptionMsg, restResponseChannel.getException().getMessage());

    // there is no exception and exception thrown in the callback.
    restResponseChannel = new MockRestResponseChannel();
    callback = new GetCallback(ambryBlobStorageService, restRequest, restResponseChannel);
    ReadableStreamChannel response = new ByteBufferReadableStreamChannel(ByteBuffer.allocate(0));
    assertTrue("Response channel is not open", response.isOpen());
    callback.onCompletion(response, null);
    assertNotNull("There is no cause of failure", restResponseChannel.getException());
  }

  /**
   * Tests for non common case scenarios for {@link PostCallback}.
   * @throws Exception
   */
  @Test
  public void postCallbackTest()
      throws Exception {
    BlobProperties blobProperties = new BlobProperties(0, "test-serviceId");
    String exceptionMsg = new String(getRandomBytes(10));
    responseHandler.reset();

    // the good case is tested through the postGetHeadDeleteTest() (result non-null, exception null)
    // Both arguments null
    RestRequest restRequest = createRestRequest(RestMethod.POST, "/", null, null);
    MockRestResponseChannel restResponseChannel = new MockRestResponseChannel();
    assertTrue("RestRequest channel is not open", restRequest.isOpen());
    PostCallback callback = new PostCallback(ambryBlobStorageService, restRequest, restResponseChannel, blobProperties);
    callback.onCompletion(null, null);
    // there should be an exception
    assertEquals("Both arguments null should have thrown exception", IllegalStateException.class,
        responseHandler.getException().getClass());
    // Nothing should be closed.
    assertTrue("RestRequest channel is not open", restRequest.isOpen());
    restRequest.close();

    // Exception is not null.
    responseHandler.reset();
    restRequest = createRestRequest(RestMethod.POST, "/", null, null);
    restResponseChannel = new MockRestResponseChannel();
    callback = new PostCallback(ambryBlobStorageService, restRequest, restResponseChannel, blobProperties);
    callback.onCompletion(null, new RuntimeException(exceptionMsg));
    assertEquals("Unexpected exception message", exceptionMsg, responseHandler.getException().getMessage());
    // Nothing should be closed.
    assertTrue("RestRequest channel is not open", restRequest.isOpen());
    restRequest.close();

    // Exception is RouterException.
    responseHandler.reset();
    restRequest = createRestRequest(RestMethod.POST, "/", null, null);
    restResponseChannel = new MockRestResponseChannel();
    callback = new PostCallback(ambryBlobStorageService, restRequest, restResponseChannel, blobProperties);
    callback.onCompletion(null, new RouterException(exceptionMsg, RouterErrorCode.UnexpectedInternalError));
    assertEquals("RouterException not converted to RestServiceException", RestServiceException.class,
        responseHandler.getException().getClass());
    if (!responseHandler.getException().getMessage().contains(exceptionMsg)) {
      fail("Exception msg [" + responseHandler.getException().getMessage() + "] does contain expected substring ["
          + exceptionMsg + "]");
    }
    // Nothing should be closed.
    assertTrue("RestRequest channel is not open", restRequest.isOpen());
    restRequest.close();

    // There are no tests for callback processing failure here because there is no good way of inducing a failure
    // and checking that the behavior is alright in PostCallback.
  }

  /**
   * Tests for non common case scenarios for {@link DeleteCallback}.
   * @throws Exception
   */
  @Test
  public void deleteCallbackTest()
      throws Exception {
    String exceptionMsg = new String(getRandomBytes(10));
    responseHandler.reset();
    // the good case is tested through the postGetHeadDeleteTest() (result null, exception null)
    // Exception is not null.
    RestRequest restRequest = createRestRequest(RestMethod.DELETE, "/", null, null);
    MockRestResponseChannel restResponseChannel = new MockRestResponseChannel();
    DeleteCallback callback = new DeleteCallback(ambryBlobStorageService, restRequest, restResponseChannel);
    callback.onCompletion(null, new RuntimeException(exceptionMsg));
    assertEquals("Unexpected exception message", exceptionMsg, responseHandler.getException().getMessage());
    // Nothing should be closed.
    assertTrue("RestRequest channel is not open", restRequest.isOpen());
    restRequest.close();

    // Exception is RouterException.
    responseHandler.reset();
    restRequest = createRestRequest(RestMethod.DELETE, "/", null, null);
    restResponseChannel = new MockRestResponseChannel();
    callback = new DeleteCallback(ambryBlobStorageService, restRequest, restResponseChannel);
    callback.onCompletion(null, new RouterException(exceptionMsg, RouterErrorCode.UnexpectedInternalError));
    assertEquals("RouterException not converted to RestServiceException", RestServiceException.class,
        responseHandler.getException().getClass());
    if (!responseHandler.getException().getMessage().contains(exceptionMsg)) {
      fail("Exception msg [" + responseHandler.getException().getMessage() + "] does contain expected substring ["
          + exceptionMsg + "]");
    }
    // Nothing should be closed.
    assertTrue("RestRequest channel is not open", restRequest.isOpen());
    restRequest.close();

    // Callback encounters a processing error (induced here via a bad RestRequest).
    restRequest = new BadRestRequest();
    // there is an exception already.
    restResponseChannel = new MockRestResponseChannel();
    callback = new DeleteCallback(ambryBlobStorageService, restRequest, restResponseChannel);
    callback.onCompletion(null, new RuntimeException(exceptionMsg));
    assertEquals("Unexpected exception message", exceptionMsg, restResponseChannel.getException().getMessage());

    // there is no exception and exception thrown in the callback.
    restResponseChannel = new MockRestResponseChannel();
    callback = new DeleteCallback(ambryBlobStorageService, restRequest, restResponseChannel);
    callback.onCompletion(null, null);
    assertNotNull("There is no cause of failure", restResponseChannel.getException());
  }

  /**
   * Tests for non common case scenarios for {@link HeadCallback}.
   * @throws Exception
   */
  @Test
  public void headCallbackTest()
      throws Exception {
    String exceptionMsg = new String(getRandomBytes(10));
    responseHandler.reset();
    // the good case is tested through the postGetHeadDeleteTest() (result non-null, exception null)
    // Both arguments null
    RestRequest restRequest = createRestRequest(RestMethod.GET, "/", null, null);
    MockRestResponseChannel restResponseChannel = new MockRestResponseChannel();
    HeadCallback callback = new HeadCallback(ambryBlobStorageService, restRequest, restResponseChannel);
    callback.onCompletion(null, null);
    // there should be an exception
    assertEquals("Both arguments null should have thrown exception", IllegalStateException.class,
        responseHandler.getException().getClass());
    // Nothing should be closed.
    assertTrue("RestRequest channel is not open", restRequest.isOpen());
    restRequest.close();

    // Exception is not null.
    responseHandler.reset();
    restRequest = createRestRequest(RestMethod.GET, "/", null, null);
    restResponseChannel = new MockRestResponseChannel();
    callback = new HeadCallback(ambryBlobStorageService, restRequest, restResponseChannel);
    callback.onCompletion(null, new RuntimeException(exceptionMsg));
    assertEquals("Unexpected exception message", exceptionMsg, responseHandler.getException().getMessage());
    // Nothing should be closed.
    assertTrue("RestRequest channel is not open", restRequest.isOpen());
    restRequest.close();

    // Exception is RouterException.
    responseHandler.reset();
    restRequest = createRestRequest(RestMethod.GET, "/", null, null);
    restResponseChannel = new MockRestResponseChannel();
    callback = new HeadCallback(ambryBlobStorageService, restRequest, restResponseChannel);
    callback.onCompletion(null, new RouterException(exceptionMsg, RouterErrorCode.UnexpectedInternalError));
    assertEquals("RouterException not converted to RestServiceException", RestServiceException.class,
        responseHandler.getException().getClass());
    if (!responseHandler.getException().getMessage().contains(exceptionMsg)) {
      fail("Exception msg [" + responseHandler.getException().getMessage() + "] does contain expected substring ["
          + exceptionMsg + "]");
    }
    // Nothing should be closed.
    assertTrue("RestRequest channel is not open", restRequest.isOpen());
    restRequest.close();

    // Callback encounters a processing error (induced here via a bad RestRequest).
    restRequest = new BadRestRequest();
    // there is an exception already.
    restResponseChannel = new MockRestResponseChannel();
    callback = new HeadCallback(ambryBlobStorageService, restRequest, restResponseChannel);
    callback.onCompletion(null, new RuntimeException(exceptionMsg));
    assertEquals("Unexpected exception message", exceptionMsg, restResponseChannel.getException().getMessage());

    // there is no exception and exception thrown in the callback.
    restResponseChannel = new MockRestResponseChannel();
    callback = new HeadCallback(ambryBlobStorageService, restRequest, restResponseChannel);
    BlobInfo blobInfo = new BlobInfo(new BlobProperties(0, "test-serviceId"), new byte[0]);
    callback.onCompletion(blobInfo, null);
    assertNotNull("There is no cause of failure", restResponseChannel.getException());
  }

  // helpers
  // general

  /**
   * Method to easily create {@link RestRequest} objects containing a specific request.
   * @param restMethod the {@link RestMethod} desired.
   * @param uri string representation of the desired URI.
   * @param headers any associated headers as a {@link JSONObject}.
   * @param contents the content that accompanies the request.
   * @return A {@link RestRequest} object that defines the request required by the input.
   * @throws JSONException
   * @throws UnsupportedEncodingException
   * @throws URISyntaxException
   */
  private RestRequest createRestRequest(RestMethod restMethod, String uri, JSONObject headers,
      List<ByteBuffer> contents)
      throws JSONException, UnsupportedEncodingException, URISyntaxException {
    JSONObject request = new JSONObject();
    request.put(MockRestRequest.REST_METHOD_KEY, restMethod);
    request.put(MockRestRequest.URI_KEY, uri);
    if (headers != null) {
      request.put(MockRestRequest.HEADERS_KEY, headers);
    }
    return new MockRestRequest(request, contents);
  }

  /**
   * Gets a byte array of length {@code size} with random bytes.
   * @param size the required length of the random byte array.
   * @return a byte array of length {@code size} with random bytes.
   */
  private byte[] getRandomBytes(int size) {
    byte[] bytes = new byte[size];
    new Random().nextBytes(bytes);
    return bytes;
  }

  /**
   * Sets headers that helps build {@link BlobProperties} on the server. See argument list for the headers that are set.
   * Any other headers have to be set explicitly.
   * @param headers the {@link JSONObject} where the headers should be set.
   * @param contentLength sets the {@link RestUtils.Headers#BLOB_SIZE} header. Required.
   * @param ttlInSecs sets the {@link RestUtils.Headers#TTL} header. Set to {@link Utils#Infinite_Time} if no
   *                  expiry.
   * @param isPrivate sets the {@link RestUtils.Headers#PRIVATE} header. Allowed values: true, false.
   * @param serviceId sets the {@link RestUtils.Headers#SERVICE_ID} header. Required.
   * @param contentType sets the {@link RestUtils.Headers#CONTENT_TYPE} header. Required and has to be a valid MIME
   *                    type.
   * @param ownerId sets the {@link RestUtils.Headers#OWNER_ID} header. Optional - if not required, send null.
   * @throws IllegalArgumentException if any of {@code headers}, {@code serviceId}, {@code contentType} is null or if
   *                                  {@code contentLength} < 0 or if {@code ttlInSecs} < -1.
   * @throws JSONException
   */
  private void setAmbryHeaders(JSONObject headers, long contentLength, long ttlInSecs, boolean isPrivate,
      String serviceId, String contentType, String ownerId)
      throws JSONException {
    if (headers != null && contentLength >= 0 && ttlInSecs >= -1 && serviceId != null && contentType != null) {
      headers.put(RestUtils.Headers.BLOB_SIZE, contentLength);
      headers.put(RestUtils.Headers.TTL, ttlInSecs);
      headers.put(RestUtils.Headers.PRIVATE, isPrivate);
      headers.put(RestUtils.Headers.SERVICE_ID, serviceId);
      headers.put(RestUtils.Headers.AMBRY_CONTENT_TYPE, contentType);
      if (ownerId != null) {
        headers.put(RestUtils.Headers.OWNER_ID, ownerId);
      }
    } else {
      throw new IllegalArgumentException("Some required arguments are null. Cannot set ambry headers");
    }
  }

  /**
   * Does a {@link AmbryBlobStorageService#handleGet(RestRequest, RestResponseChannel)} and returns
   * the result, if any. If an exception occurs during the operation, throws the exception.
   * @param restRequest the {@link RestRequest} that needs to be submitted to the {@link AmbryBlobStorageService}.
   * @param restResponseChannel the {@link RestResponseChannel} to use to return the response.
   * @return the response as a {@link ReadableStreamChannel}.
   * @throws Exception
   */
  private ReadableStreamChannel doGet(RestRequest restRequest, RestResponseChannel restResponseChannel)
      throws Exception {
    responseHandler.reset();
    ambryBlobStorageService.handleGet(restRequest, restResponseChannel);
    if (responseHandler.awaitResponseSubmission(1, TimeUnit.SECONDS)) {
      if (responseHandler.getException() != null) {
        throw responseHandler.getException();
      }
    } else {
      throw new IllegalStateException("handleGet() timed out");
    }
    return responseHandler.getResponse();
  }

  /**
   * Does a {@link AmbryBlobStorageService#handlePost(RestRequest, RestResponseChannel)}. If an exception occurs during
   * the operation, throws the exception.
   * @param restRequest the {@link RestRequest} that needs to be submitted to the {@link AmbryBlobStorageService}.
   * @param restResponseChannel the {@link RestResponseChannel} to use to return the response.
   * @throws Exception
   */
  private void doPost(RestRequest restRequest, RestResponseChannel restResponseChannel)
      throws Exception {
    responseHandler.reset();
    ambryBlobStorageService.handlePost(restRequest, restResponseChannel);
    if (responseHandler.awaitResponseSubmission(1, TimeUnit.SECONDS)) {
      if (responseHandler.getException() != null) {
        throw responseHandler.getException();
      }
    } else {
      throw new IllegalStateException("handlePost() timed out");
    }
  }

  /**
   * Does a {@link AmbryBlobStorageService#handleDelete(RestRequest, RestResponseChannel)}. If an exception occurs
   * during the operation, throws the exception.
   * @param restRequest the {@link RestRequest} that needs to be submitted to the {@link AmbryBlobStorageService}.
   * @param restResponseChannel the {@link RestResponseChannel} to use to return the response.
   * @throws Exception
   */
  private void doDelete(RestRequest restRequest, RestResponseChannel restResponseChannel)
      throws Exception {
    responseHandler.reset();
    ambryBlobStorageService.handleDelete(restRequest, restResponseChannel);
    if (responseHandler.awaitResponseSubmission(1, TimeUnit.SECONDS)) {
      if (responseHandler.getException() != null) {
        throw responseHandler.getException();
      }
    } else {
      throw new IllegalStateException("handleDelete() timed out");
    }
  }

  /**
   * Does a {@link AmbryBlobStorageService#handleHead(RestRequest, RestResponseChannel)}. If an exception occurs during
   * the operation, throws the exception.
   * @param restRequest the {@link RestRequest} that needs to be submitted to the {@link AmbryBlobStorageService}.
   * @param restResponseChannel the {@link RestResponseChannel} to use to return the response.
   * @throws Exception
   */
  private void doHead(RestRequest restRequest, RestResponseChannel restResponseChannel)
      throws Exception {
    responseHandler.reset();
    ambryBlobStorageService.handleHead(restRequest, restResponseChannel);
    if (responseHandler.awaitResponseSubmission(1, TimeUnit.SECONDS)) {
      if (responseHandler.getException() != null) {
        throw responseHandler.getException();
      }
    } else {
      throw new IllegalStateException("handleHead() timed out");
    }
  }

  // Constructor helpers

  /**
   * Sets up and gets an instance of {@link AmbryBlobStorageService}.
   * @return an instance of {@link AmbryBlobStorageService}.
   */
  private AmbryBlobStorageService getAmbryBlobStorageService() {
    // dud properties. pick up defaults
    Properties properties = new Properties();
    VerifiableProperties verifiableProperties = new VerifiableProperties(properties);
    FrontendConfig frontendConfig = new FrontendConfig(verifiableProperties);
    FrontendMetrics frontendMetrics = new FrontendMetrics(new MetricRegistry());
    return new AmbryBlobStorageService(frontendConfig, frontendMetrics, CLUSTER_MAP, responseHandler, router);
  }

  // nullInputsForFunctionsTest() helpers

  /**
   * Checks for reaction to null input in {@code methodName} in {@link AmbryBlobStorageService}.
   * @param methodName the name of the method to invoke.
   * @throws Exception
   */
  private void doNullInputsForFunctionsTest(String methodName)
      throws Exception {
    Method method =
        AmbryBlobStorageService.class.getDeclaredMethod(methodName, RestRequest.class, RestResponseChannel.class);
    RestRequest restRequest = createRestRequest(RestMethod.GET, "/", null, null);
    RestResponseChannel restResponseChannel = new MockRestResponseChannel();

    responseHandler.reset();
    try {
      method.invoke(ambryBlobStorageService, null, restResponseChannel);
      fail("Method [" + methodName + "] should have failed because RestRequest is null");
    } catch (InvocationTargetException e) {
      assertEquals("Unexpected exception class", IllegalArgumentException.class, e.getTargetException().getClass());
    }

    responseHandler.reset();
    try {
      method.invoke(ambryBlobStorageService, restRequest, null);
      fail("Method [" + methodName + "] should have failed because RestResponseChannel is null");
    } catch (InvocationTargetException e) {
      assertEquals("Unexpected exception class", IllegalArgumentException.class, e.getTargetException().getClass());
    }
  }

  // runtimeExceptionRouterTest() helpers

  /**
   * Tests reactions of various methods of {@link AmbryBlobStorageService} to a {@link Router} that throws
   * {@link RuntimeException}.
   * @param restMethod used to determine the method to invoke in {@link AmbryBlobStorageService}.
   * @throws Exception
   */
  private void doRuntimeExceptionRouterTest(RestMethod restMethod)
      throws Exception {
    RestRequest restRequest = createRestRequest(restMethod, "/", null, null);
    RestResponseChannel restResponseChannel = new MockRestResponseChannel();
    try {
      switch (restMethod) {
        case GET:
          doGet(restRequest, restResponseChannel);
          fail("GET should have detected a RestServiceException because of a bad router");
          break;
        case POST:
          JSONObject headers = new JSONObject();
          setAmbryHeaders(headers, 0, Utils.Infinite_Time, false, "test-serviceID", "text/plain", "test-ownerId");
          restRequest = createRestRequest(restMethod, "/", headers, null);
          doPost(restRequest, restResponseChannel);
          fail("POST should have detected a RestServiceException because of a bad router");
          break;
        case DELETE:
          doDelete(restRequest, restResponseChannel);
          fail("DELETE should have detected a RestServiceException because of a bad router");
          break;
        case HEAD:
          doHead(restRequest, restResponseChannel);
          fail("HEAD should have detected a RestServiceException because of a bad router");
          break;
        default:
          throw new IllegalArgumentException("Unrecognized RestMethod: " + restMethod);
      }
    } catch (RuntimeException e) {
      assertEquals("Unexpected error message", InMemoryRouter.OPERATION_THROW_EARLY_RUNTIME_EXCEPTION, e.getMessage());
    }
  }

  // postGetHeadDeleteTest() helpers

  /**
   * Posts a blob with the given {@code headers} and {@code content}.
   * @param headers the headers of the new blob that get converted to blob properties.
   * @param content the content of the blob.
   * @return the blob ID of the blob.
   * @throws Exception
   */
  public String postBlobAndVerify(JSONObject headers, ByteBuffer content)
      throws Exception {
    List<ByteBuffer> contents = new LinkedList<ByteBuffer>();
    contents.add(content);
    contents.add(null);
    RestRequest restRequest = createRestRequest(RestMethod.POST, "/", headers, contents);
    MockRestResponseChannel restResponseChannel = new MockRestResponseChannel();
    doPost(restRequest, restResponseChannel);
    assertEquals("Unexpected response status", ResponseStatus.Created, restResponseChannel.getResponseStatus());
    assertTrue("No Date header", restResponseChannel.getHeader(RestUtils.Headers.DATE) != null);
    assertTrue("No " + RestUtils.Headers.CREATION_TIME,
        restResponseChannel.getHeader(RestUtils.Headers.CREATION_TIME) != null);
    assertEquals("Content-Length is not 0", "0", restResponseChannel.getHeader(RestUtils.Headers.CONTENT_LENGTH));
    String blobId = restResponseChannel.getHeader(RestUtils.Headers.LOCATION);
    if (blobId == null) {
      fail("postBlobAndVerify did not return a blob ID");
    }
    return blobId;
  }

  /**
   * Gets the blob with blob ID {@code blobId} and verifies that the headers and content match with what is expected.
   * @param blobId the blob ID of the blob to GET.
   * @param expectedHeaders the expected headers in the response.
   * @param expectedContent the expected content of the blob.
   * @throws Exception
   */
  public void getBlobAndVerify(String blobId, JSONObject expectedHeaders, ByteBuffer expectedContent)
      throws Exception {
    RestRequest restRequest = createRestRequest(RestMethod.GET, blobId, null, null);
    MockRestResponseChannel restResponseChannel = new MockRestResponseChannel();
    ReadableStreamChannel response = doGet(restRequest, restResponseChannel);
    assertEquals("Unexpected response status", ResponseStatus.Ok, restResponseChannel.getResponseStatus());
    checkCommonGetHeadHeaders(restResponseChannel, expectedHeaders);
    CopyingAsyncWritableChannel channel = new CopyingAsyncWritableChannel((int) response.getSize());
    response.readInto(channel, null).get();
    assertArrayEquals("GET content does not match original content", expectedContent.array(), channel.getData());
  }

  /**
   * Gets the headers of the blob with blob ID {@code blobId} and verifies them against what is expected.
   * @param blobId the blob ID of the blob to HEAD.
   * @param expectedHeaders the expected headers in the response.
   * @throws Exception
   */
  private void getHeadAndVerify(String blobId, JSONObject expectedHeaders)
      throws Exception {
    RestRequest restRequest = createRestRequest(RestMethod.HEAD, blobId, null, null);
    MockRestResponseChannel restResponseChannel = new MockRestResponseChannel();
    doHead(restRequest, restResponseChannel);
    assertEquals("Unexpected response status", ResponseStatus.Ok, restResponseChannel.getResponseStatus());
    checkCommonGetHeadHeaders(restResponseChannel, expectedHeaders);
    assertEquals("Content-Length does not match blob size", expectedHeaders.getString(RestUtils.Headers.BLOB_SIZE),
        restResponseChannel.getHeader(RestUtils.Headers.CONTENT_LENGTH));
    assertEquals(RestUtils.Headers.SERVICE_ID + " does not match",
        expectedHeaders.getString(RestUtils.Headers.SERVICE_ID),
        restResponseChannel.getHeader(RestUtils.Headers.SERVICE_ID));
    assertEquals(RestUtils.Headers.PRIVATE + " does not match", expectedHeaders.getString(RestUtils.Headers.PRIVATE),
        restResponseChannel.getHeader(RestUtils.Headers.PRIVATE));
    assertEquals(RestUtils.Headers.AMBRY_CONTENT_TYPE + " does not match",
        expectedHeaders.getString(RestUtils.Headers.AMBRY_CONTENT_TYPE),
        restResponseChannel.getHeader(RestUtils.Headers.AMBRY_CONTENT_TYPE));
    assertTrue(RestUtils.Headers.CREATION_TIME + " header missing",
        restResponseChannel.getHeader(RestUtils.Headers.CREATION_TIME) != null);
    if (expectedHeaders.getLong(RestUtils.Headers.TTL) != Utils.Infinite_Time) {
      assertEquals(RestUtils.Headers.TTL + " does not match", expectedHeaders.getString(RestUtils.Headers.TTL),
          restResponseChannel.getHeader(RestUtils.Headers.TTL));
    }
    if (expectedHeaders.has(RestUtils.Headers.OWNER_ID)) {
      assertEquals(RestUtils.Headers.OWNER_ID + " does not match",
          expectedHeaders.getString(RestUtils.Headers.OWNER_ID),
          restResponseChannel.getHeader(RestUtils.Headers.OWNER_ID));
    }
    verifyUserMetadataHeaders(expectedHeaders, restResponseChannel);
  }

  /**
   * Verifies User metadata headers from output, to that sent in during input
   * @param expectedHeaders the expected headers in the response.
   * @param restResponseChannel the {@link RestResponseChannel} which contains the response.
   * @throws JSONException
   */
  private void verifyUserMetadataHeaders(JSONObject expectedHeaders, MockRestResponseChannel restResponseChannel)
      throws JSONException {
    Iterator itr = expectedHeaders.keys();
    while (itr.hasNext()) {
      String key = (String) itr.next();
      if (key.startsWith(RestUtils.Headers.UserMetaData_Header_Prefix)) {
        String outValue = restResponseChannel.getHeader(key);
        assertEquals("Value for " + key + "does not match in user metadata", expectedHeaders.getString(key), outValue);
      }
    }
  }

  /**
   * Deletes the blob with blob ID {@code blobId} and verifies the response returned.
   * @param blobId the blob ID of the blob to DELETE.
   * @throws Exception
   */
  private void deleteBlobAndVerify(String blobId)
      throws Exception {
    RestRequest restRequest = createRestRequest(RestMethod.DELETE, blobId, null, null);
    MockRestResponseChannel restResponseChannel = new MockRestResponseChannel();
    doDelete(restRequest, restResponseChannel);
    assertEquals("Unexpected response status", ResponseStatus.Accepted, restResponseChannel.getResponseStatus());
    assertTrue("No Date header", restResponseChannel.getHeader(RestUtils.Headers.DATE) != null);
    assertEquals("Content-Length is not 0", "0", restResponseChannel.getHeader(RestUtils.Headers.CONTENT_LENGTH));
  }

  /**
   * Checks headers that are common to HEAD and GET.
   * @param restResponseChannel the {@link RestResponseChannel} to check headers on.
   * @param expectedHeaders the expected headers.
   * @throws JSONException
   */
  private void checkCommonGetHeadHeaders(MockRestResponseChannel restResponseChannel, JSONObject expectedHeaders)
      throws JSONException {
    assertEquals("Content-Type does not match", expectedHeaders.getString(RestUtils.Headers.AMBRY_CONTENT_TYPE),
        restResponseChannel.getHeader(RestUtils.Headers.CONTENT_TYPE));
    assertTrue("No Date header", restResponseChannel.getHeader(RestUtils.Headers.DATE) != null);
    assertTrue("No Last-Modified header", restResponseChannel.getHeader("Last-Modified") != null);
    assertEquals(RestUtils.Headers.BLOB_SIZE + " does not match",
        expectedHeaders.getString(RestUtils.Headers.BLOB_SIZE),
        restResponseChannel.getHeader(RestUtils.Headers.BLOB_SIZE));
  }
}

/**
 * An implementation of {@link RestResponseHandler} that stores a submitted response/exception and signals the fact
 * that the response has been submitted. A single instance can handle only a single response at a time. To reuse, call
 * {@link #reset()}.
 */
class FrontendTestResponseHandler implements RestResponseHandler {
  private final CountDownLatch responseSubmitted = new CountDownLatch(1);
  private volatile ReadableStreamChannel response = null;
  private volatile Exception exception = null;
  private volatile boolean serviceRunning = false;

  @Override
  public void start() {
    serviceRunning = true;
  }

  @Override
  public void shutdown() {
    serviceRunning = false;
  }

  @Override
  public void handleResponse(RestRequest restRequest, RestResponseChannel restResponseChannel,
      ReadableStreamChannel response, Exception exception)
      throws RestServiceException {
    if (serviceRunning) {
      this.response = response;
      this.exception = exception;
      restResponseChannel.onResponseComplete(exception);
      responseSubmitted.countDown();
    } else {
      throw new RestServiceException("Response handler inactive", RestServiceErrorCode.RequestResponseQueuingFailure);
    }
  }

  /**
   * Wait for response to be submitted.
   * @param timeout the length of time to wait for.
   * @param timeUnit the time unit of {@code timeout}.
   * @return {@code true} if response was submitted within {@code timeout}. {@code false} otherwise.
   * @throws InterruptedException
   */
  public boolean awaitResponseSubmission(long timeout, TimeUnit timeUnit)
      throws InterruptedException {
    return responseSubmitted.await(timeout, timeUnit);
  }

  /**
   * Gets the exception that was submitted, if any. Returns null if queried before response is submitted.
   * @return exception that that was submitted, if any.
   */
  public Exception getException() {
    return exception;
  }

  /**
   * Gets the response that was submitted, if any. Returns null if queried before response is submitted.
   * @return response that that was submitted as a {@link ReadableStreamChannel}.
   */
  public ReadableStreamChannel getResponse() {
    return response;
  }

  /**
   * Resets state so that this instance can be reused.
   */
  public void reset() {
    response = null;
    exception = null;
  }
}

/**
 * A bad implementation of {@link RestRequest}. Just throws exceptions.
 */
class BadRestRequest implements RestRequest {

  @Override
  public RestMethod getRestMethod() {
    return null;
  }

  @Override
  public String getPath() {
    throw new IllegalStateException("Not implemented");
  }

  @Override
  public String getUri() {
    return null;
  }

  @Override
  public Map<String, Object> getArgs() {
    throw new IllegalStateException("Not implemented");
  }

  @Override
  public boolean isOpen() {
    throw new IllegalStateException("Not implemented");
  }

  @Override
  public void close()
      throws IOException {
    throw new IOException("Not implemented");
  }

  @Override
  public RestRequestMetricsTracker getMetricsTracker() {
    return new RestRequestMetricsTracker();
  }

  @Override
  public long getSize() {
    return -1;
  }

  @Override
  public Future<Long> readInto(AsyncWritableChannel asyncWritableChannel, Callback<Long> callback) {
    throw new IllegalStateException("Not implemented");
  }
}

/**
 * A bad implementation of {@link ReadableStreamChannel}. Just throws exceptions.
 */
class BadRSC implements ReadableStreamChannel {

  @Override
  public long getSize() {
    return -1;
  }

  @Override
  public Future<Long> readInto(AsyncWritableChannel asyncWritableChannel, Callback<Long> callback) {
    throw new IllegalStateException("Not implemented");
  }

  @Override
  public boolean isOpen() {
    throw new IllegalStateException("Not implemented");
  }

  @Override
  public void close()
      throws IOException {
    throw new IOException("Not implemented");
  }
}