package iudx.data.ingestion.server.apiserver;

import static iudx.data.ingestion.server.apiserver.response.ResponseUrn.*;
import static iudx.data.ingestion.server.apiserver.util.Constants.*;

import java.util.HashSet;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import iudx.data.ingestion.server.apiserver.handlers.AuthHandler;
import iudx.data.ingestion.server.apiserver.handlers.FailureHandler;
import iudx.data.ingestion.server.apiserver.handlers.ValidationHandler;
import iudx.data.ingestion.server.apiserver.response.ResponseUrn;
import iudx.data.ingestion.server.apiserver.service.CatalogueService;
import iudx.data.ingestion.server.apiserver.util.Constants;
import iudx.data.ingestion.server.apiserver.util.HttpStatusCode;
import iudx.data.ingestion.server.apiserver.util.RequestType;
import iudx.data.ingestion.server.authenticator.AuthenticationService;
import iudx.data.ingestion.server.databroker.DataBrokerService;

/**
 * The Data Ingestion API Verticle.
 * <h1>Data Ingestion API Verticle</h1>
 * <p>
 * The API Server verticle implements the IUDX Data Ingestion APIs. It handles the API requests from
 * the clients and interacts with the associated Service to respond.
 * </p>
 *
 * @version 1.0
 * @see io.vertx.core.Vertx
 * @see io.vertx.core.AbstractVerticle
 * @see io.vertx.core.http.HttpServer
 * @see io.vertx.ext.web.Router
 * @see io.vertx.servicediscovery.ServiceDiscovery
 * @see io.vertx.servicediscovery.types.EventBusService
 * @since 2021-08-04
 */

public class ApiServerVerticle extends AbstractVerticle {
  private static final Logger LOGGER = LogManager.getLogger(ApiServerVerticle.class);

  /**
   * Service addresses
   */
  private static final String AUTH_SERVICE_ADDRESS = "iudx.data.ingestion.authentication.service";
  private static final String BROKER_SERVICE_ADDRESS = "iudx.data.ingestion.broker.service";

  private HttpServer server;
  private Router router;
  private int port = 18443;
  private boolean isSSL, isProduction;
  private String keystore;
  private String keystorePassword;
  private DataBrokerService databroker;
  private CatalogueService catalogueService;
  private AuthenticationService authenticationService;

  @Override
  public void start() throws Exception {

    Set<String> allowedHeaders = new HashSet<>();
    allowedHeaders.add(Constants.HEADER_ACCEPT);
    allowedHeaders.add(Constants.HEADER_TOKEN);
    allowedHeaders.add(Constants.HEADER_CONTENT_LENGTH);
    allowedHeaders.add(Constants.HEADER_CONTENT_TYPE);
    allowedHeaders.add(Constants.HEADER_HOST);
    allowedHeaders.add(Constants.HEADER_ORIGIN);
    allowedHeaders.add(Constants.HEADER_REFERER);
    allowedHeaders.add(Constants.HEADER_ALLOW_ORIGIN);

    Set<HttpMethod> allowedMethods = new HashSet<>();
    allowedMethods.add(HttpMethod.POST);
    allowedMethods.add(HttpMethod.OPTIONS);
    /* Define the APIs, methods, endpoints and associated methods. */

    router = Router.router(vertx);
    router.route().handler(
        CorsHandler.create("*").allowedHeaders(allowedHeaders).allowedMethods(allowedMethods));

    router.route().handler(requestHandler -> {
      requestHandler.response()
          .putHeader("Cache-Control", "no-cache, no-store,  must-revalidate,max-age=0")
          .putHeader("Pragma", "no-cache").putHeader("Expires", "0")
          .putHeader("X-Content-Type-Options", "nosniff");
      requestHandler.next();
    });

    router.route().handler(BodyHandler.create());

    FailureHandler validationsFailureHandler = new FailureHandler();
    ValidationHandler postEntitiesValidationHandler =
        new ValidationHandler(vertx, RequestType.ENTITY);

    router.post(Constants.NGSILD_ENTITIES_URL).consumes(Constants.APPLICATION_JSON)
        .handler(postEntitiesValidationHandler)
        .handler(AuthHandler.create(vertx))
        .handler(this::handleEntitiesPostQuery).failureHandler(validationsFailureHandler);

    /* Read ssl configuration. */
    isSSL = config().getBoolean("ssl");

    /* Read server deployment configuration. */
    isProduction = config().getBoolean("production");

    HttpServerOptions serverOptions = new HttpServerOptions();

    if (isSSL) {
      LOGGER.debug("Info: Starting HTTPs server");

      /* Read the configuration and set the HTTPs server properties. */

      keystore = config().getString("keystore");
      LOGGER.info("KEYSTORE: " + keystore);
      keystorePassword = config().getString("keystorePassword");

      /* Setup the HTTPs server properties, APIs and port. */

      serverOptions.setSsl(true)
          .setKeyStoreOptions(new JksOptions().setPath(keystore).setPassword(keystorePassword));

    } else {
      LOGGER.debug("Info: Starting HTTP server");

      /* Setup the HTTP server properties, APIs and port. */

      serverOptions.setSsl(false);
      if (isProduction) {
        port = 80;
      } else {
        port = 8080;
      }
    }

    serverOptions.setCompressionSupported(true).setCompressionLevel(5);
    server = vertx.createHttpServer(serverOptions);
    server.requestHandler(router).listen(port);

    databroker = DataBrokerService.createProxy(vertx, BROKER_SERVICE_ADDRESS);
    authenticationService = AuthenticationService.createProxy(vertx, AUTH_SERVICE_ADDRESS);
    catalogueService = new CatalogueService(vertx, config());
  }

  /**
   * This method is used to handle all data publication for endpoint /ngsi-ld/v1/entities.
   *
   * @param routingContext RoutingContext Object
   */

  private void handleEntitiesPostQuery(RoutingContext routingContext) {
    LOGGER.debug("Info:handleEntitiesPostQuery method started.");
    JsonObject requestJson = routingContext.getBodyAsJson();
    LOGGER.debug("Info: request Json :: ;" + requestJson);

    // ValidatorsHandlersFactory validationFactory = new ValidatorsHandlersFactory();
    String id = requestJson.getString(ID);
    LOGGER.info("ID " + id);
    /* Handles HTTP response from server to client */
    HttpServerResponse response = routingContext.response();
    Future<Boolean> isIdExist = catalogueService.isItemExist(id);
    LOGGER.info("Success: " + isIdExist.succeeded());
    isIdExist.onComplete(IdExisthandler -> {
      if (IdExisthandler.succeeded()) {
        LOGGER.info("Success: Ingestion Success");
        databroker.publishData(requestJson, handler -> {
          if (handler.succeeded()) {
            LOGGER.info("Success: Ingestion Success");
            handleSuccessResponse(response, 200, handler.result().toString());
          } else if (handler.failed()) {
            LOGGER.error("Fail: Ingestion Fail");
            handleFailedResponse(response, 400, ResponseUrn.INVALID_PAYLOAD_FORMAT);
          }
        });
      } else {
        LOGGER.error("Fail: ID does not exist. ");
        handleFailedResponse(response, 404, ResponseUrn.RESOURCE_NOT_FOUND);
      }
    });
  }

  /**
   * handle HTTP response.
   *
   * @param response response object
   * @param statusCode Http status for response
   * @param result response
   */

  private void handleSuccessResponse(HttpServerResponse response, int statusCode, String result) {
    JsonObject res = new JsonObject();
    res.put(JSON_TYPE, ResponseUrn.SUCCESS.getUrn())
        .put(JSON_TITLE, ResponseUrn.SUCCESS.getMessage())
        .put(JSON_DETAIL, ResponseUrn.SUCCESS.getMessage());
    response.putHeader(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON).setStatusCode(statusCode)
        .end(res.toString());
  }

  private void handleFailedResponse(HttpServerResponse response, int statusCode, ResponseUrn failureType) {
    HttpStatusCode status = HttpStatusCode.getByValue(statusCode);
    response.putHeader(CONTENT_TYPE, APPLICATION_JSON).setStatusCode(status.getValue())
        .end(generateResponse(failureType, status).toString());
  }

  private JsonObject generateResponse(ResponseUrn urn, HttpStatusCode statusCode) {
    return new JsonObject().put(JSON_TYPE, urn.getUrn()).put(JSON_DETAIL,
        statusCode.getDescription());
  }


}