/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.soroushbot.component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.component.soroushbot.models.Endpoint;
import org.apache.camel.component.soroushbot.models.SoroushMessage;
import org.apache.camel.component.soroushbot.models.response.UploadFileResponse;
import org.apache.camel.component.soroushbot.service.SoroushService;
import org.apache.camel.component.soroushbot.utils.BackOffStrategy;
import org.apache.camel.component.soroushbot.utils.ExponentialBackOffStrategy;
import org.apache.camel.component.soroushbot.utils.FixedBackOffStrategy;
import org.apache.camel.component.soroushbot.utils.LinearBackOffStrategy;
import org.apache.camel.component.soroushbot.utils.MaximumConnectionRetryReachedException;
import org.apache.camel.component.soroushbot.utils.SoroushException;
import org.apache.camel.component.soroushbot.utils.StringUtils;
import org.apache.camel.spi.Metadata;
import org.apache.camel.spi.UriEndpoint;
import org.apache.camel.spi.UriParam;
import org.apache.camel.spi.UriPath;
import org.apache.camel.support.DefaultEndpoint;
import org.glassfish.jersey.media.multipart.MultiPart;
import org.glassfish.jersey.media.multipart.file.StreamDataBodyPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * this class represents Soroush Endpoint, it is also a bean containing the configuration of the Endpoint
 */
@UriEndpoint(firstVersion = "3.0", scheme = "soroush", title = "SoroushBot", syntax = "soroush:<endpoint>[/authorizationToken][?options]", label = "chat")
public class SoroushBotEndpoint extends DefaultEndpoint {
    private static Logger log = LoggerFactory.getLogger(SoroushBotEndpoint.class);

    @UriPath(name = "endpoint", enums = "Endpoint", javaType = "Endpoint",
            description = "The endpoint type. it support `getMessage` as consumer and `sendMessage`,`uploadFile`,`downloadFile` as producer")
    @Metadata(required = true)
    Endpoint type;

    @UriPath(label = "global,security", description = "The authorization token for using"
            + " the bot (ask the @mrbot) e.g. 9yDv09nqKvP9CkBGKNmKQHir1dj2qLpN-YWa8hP7Rm3LK\"\n"
            + "            + \"3MqQXYdXZIA5W4e0agPiUb-3eUKX69ozUNdY9yZBMlJiwnlksslkjWjsxcARo5cYtDnKTElig0xa\"\n"
            + "            + \"e1Cjt1Bexz2cw-t6cJ7t1f ")
    @UriParam(label = "global,security", description = "The authorization token for using"
            + " the bot. if uri path does not contain authorization token, this token will be used.", secret = true)
    String authorizationToken;
    @UriParam(label = "global", description = "Connection timeout in ms when connecting to soroush API", defaultValue = "30000")
    Integer connectionTimeout = 30000;
    @UriParam(label = "global", description = "Maximum connection retry when fail to connect to soroush API, if the quota is reached,"
            + " `MaximumConnectionRetryReachedException` is thrown for that message.", defaultValue = "4")
    Integer maxConnectionRetry = 4;
    @UriParam(label = "getMessage,consumer", description = "Number of Thread created by consumer in the route."
            + " if you use this method for parallelism, it is guaranteed that messages from same user always execute in the same"
            + " thread and therefore messages from the same user are processed sequentially", defaultValue = "1",
            defaultValueNote = "using SoroushBotSingleThreadConsumer")
    Integer concurrentConsumers = 1;
    @UriParam(label = "getMessage,consumer", description = "Maximum capacity of each queue when `concurrentConsumers` is greater than 1."
            + " if a queue become full, every message that should go to that queue will be dropped. If `bridgeErrorHandler`"
            + " is set to `true`, an exchange with `CongestionException` is directed to ErrorHandler. You can then processed the"
            + " error using `onException(CongestionException.class)` route", defaultValue = "0", defaultValueNote = "infinite capacity")
    Integer queueCapacityPerThread = 0;
    @UriParam(label = "sendMessage", description = "Automatically upload attachments when a message goes to the sendMessage endpoint "
            + "and the `SoroushMessage.file` (`SoroushMessage.thumbnail`) has been set and `SoroushMessage.fileUrl`(`SoroushMessage.thumbnailUrl`) is null",
            defaultValue = "true")
    Boolean autoUploadFile = true;
    @UriParam(label = "sendMessage,uploadFile", description = "Force to  upload `SoroushMessage.file`(`SoroushMessage.thumbnail`) if exists, even if the "
            + "`SoroushMessage.fileUrl`(`SoroushMessage.thumbnailUrl`) is not null in the message", defaultValue = "false")
    Boolean forceUpload = false;
    @UriParam(label = "getMessage,downloadFile", description = "If true, when downloading an attached file, thumbnail will be downloaded if provided in the message."
            + " Otherwise, only the file will be downloaded ", defaultValue = "true")
    Boolean downloadThumbnail = true;
    @UriParam(label = "downloadFile", description = "Force to download `SoroushMessage.fileUrl`(`SoroushMessage.thumbnailUrl`)"
            + " if exists, even if the `SoroushMessage.file`(`SoroushMessage.thumbnail`) was not null in that message",
            defaultValue = "false")
    Boolean forceDownload = false;
    @UriParam(label = "getMessage", description = "Automatically download `SoroushMessage.fileUrl` and `SoroushMessage.thumbnailUrl` "
            + "if exists for the message and store them in `SoroushMessage.file` and `SoroushMessage.thumbnail` field ",
            defaultValue = "false")
    Boolean autoDownload = false;
    @UriParam(label = "global", description = "Waiting time before retry failed request (Millisecond)."
            + " If backOffStrategy is not Fixed this is the based value for computing back off waiting time."
            + " the first retry is always happen immediately after failure and retryWaitingTime do not apply to the first retry.",
            defaultValue = "1000")
    Long retryWaitingTime = 1000L;
    @UriParam(label = "global", description = "The strategy to backoff in case of connection failure. Currently 3 strategies are supported:"
            + " 1. `Exponential` (default): It multiply `retryWaitingTime` by `retryExponentialCoefficient` after each connection failure."
            + " 2. `Linear`: It increase `retryWaitingTime` by `retryLinearIncrement` after each connection failure."
            + " 3. `Fixed`: Always use `retryWaitingTime` as the time between retries.",
            defaultValue = "Exponential")
    String backOffStrategy = "Exponential";
    @UriParam(label = "global", description = "Coefficient to compute back off time when using `Exponential` Back Off strategy", defaultValue = "2")
    Long retryExponentialCoefficient = 2L;
    @UriParam(label = "global", description = "The amount of time (in millisecond) which adds to waiting time when using `Linear` back off strategy", defaultValue = "10000")
    Long retryLinearIncrement = 10000L;
    @UriParam(label = "global", description = "Maximum amount of time (in millisecond) a thread wait before retrying failed request.",
            defaultValue = "3600000")
    Long maxRetryWaitingTime = 3600000L;
    @UriParam(label = "getMessage", description = "The timeout in millisecond to reconnect the existing getMessage connection"
            + " to ensure that the connection is always live and does not dead without notifying the bot. this value should not be changed.",
            defaultValue = "300000")
    private long reconnectIdleConnectionTimeout = 5 * 60 * 1000;
    /**
     * lazy instance of {@link WebTarget} to used for uploading file to soroush Server, since the url is always the same, we reuse this WebTarget for all requests
     */
    private WebTarget uploadFileTarget;
    /**
     * lazy instance of webTarget to used for send message to soroush Server, since the url is always the same, we reuse this WebTarget for all requests
     */
    private WebTarget sendMessageTarget;
    private BackOffStrategy backOffStrategyHelper;

    public SoroushBotEndpoint(String endpointUri, SoroushBotComponent component) {
        super(endpointUri, component);
    }


    /**
     * @return supported Soroush endpoint as string to display in error.
     */
    private String getSupportedEndpointAsString() {
        return "[" + String.join(", ", getSupportedEndpoint().stream().map(Endpoint::value).collect(Collectors.toList())) + "]";
    }

    /**
     * @return supported Soroush endpoint by this component which is all Soroush Bot API
     */
    private List<Endpoint> getSupportedEndpoint() {
        return Arrays.asList(Endpoint.values());
    }

    /**
     * Sets the remaining configuration parameters available in the URI.
     *
     * @param remaining                 the URI part after the scheme
     * @param defaultAuthorizationToken the default authorization token to use if not present in the URI
     * @param uri                       full uri
     */
    void updatePathConfiguration(String remaining, String defaultAuthorizationToken, String uri) {
        List<String> pathParts;
        if (remaining == null) {
            throw new IllegalArgumentException("Unexpected URI format. Expected soroush://" + getSupportedEndpointAsString() + "[/<authorizationToken>][?options]', found " + uri);
        }
        pathParts = Arrays.asList(remaining.split("/"));
        for (int i = pathParts.size() - 1; i >= 0; i--) {
            if (pathParts.get(i).trim().isEmpty()) {
                pathParts.remove(i);
            }
        }

        if (pathParts.size() > 2 || pathParts.size() == 0) {
            throw new IllegalArgumentException("Unexpected URI format. Expected soroush://" + getSupportedEndpointAsString() + "[/<authorizationToken>][?options]', found " + uri);
        }
        for (Endpoint supported : getSupportedEndpoint()) {
            if (supported.value().equals(pathParts.get(0))) {
                type = supported;
            }
        }
        if (type == null) {
            throw new IllegalArgumentException("Unexpected URI format. Expected soroush://" + getSupportedEndpointAsString() + "[/<authorizationToken>][?options]', found " + uri);
        }
        if (this.authorizationToken == null) {
            String authorizationToken = defaultAuthorizationToken;
            if (pathParts.size() > 1) {
                authorizationToken = pathParts.get(1);
            }
            this.authorizationToken = authorizationToken;
        }
        if (authorizationToken == null || authorizationToken.trim().isEmpty()) {
            throw new IllegalArgumentException("The authorization token must be provided and cannot be empty");
        }
    }

    /**
     * check and fix invalid value in uri parameter.
     */
    void normalizeConfiguration() {
        if (connectionTimeout == null) {
            connectionTimeout = 0;
        }
        if (maxConnectionRetry == null) {
            maxConnectionRetry = 0;
        }
        if (reconnectIdleConnectionTimeout <= 0) {
            reconnectIdleConnectionTimeout = 5 * 60 * 1000;
        }
        connectionTimeout = Math.max(0, connectionTimeout);
        maxConnectionRetry = Math.max(0, maxConnectionRetry);
        retryExponentialCoefficient = Math.max(1, retryExponentialCoefficient);
        retryLinearIncrement = Math.max(0, retryLinearIncrement);

        if (backOffStrategy.equalsIgnoreCase("fixed")) {
            backOffStrategyHelper = new FixedBackOffStrategy(retryWaitingTime, maxRetryWaitingTime);
        } else if (backOffStrategy.equalsIgnoreCase("linear")) {
            backOffStrategyHelper = new LinearBackOffStrategy(retryWaitingTime, retryLinearIncrement, maxRetryWaitingTime);
        } else {
            backOffStrategyHelper = new ExponentialBackOffStrategy(retryWaitingTime, retryExponentialCoefficient, maxRetryWaitingTime);
        }
    }

    /**
     * create producer based on uri {@link Endpoint}
     *
     * @return created producer
     */
    @Override
    public Producer createProducer() {
        if (type == Endpoint.sendMessage) {
            return new SoroushBotSendMessageProducer(this);
        }
        if (type == Endpoint.uploadFile) {
            return new SoroushBotUploadFileProducer(this);
        }
        if (type == Endpoint.downloadFile) {
            return new SoroushBotDownloadFileProducer(this);
        } else {
            throw new IllegalArgumentException("only [" + Endpoint.sendMessage + ", " + Endpoint.downloadFile + ", " + Endpoint.uploadFile
                    + "] supported for producer(from) and process");
        }
    }

    /**
     * create consumer based on concurrentConsumers value,
     * if concurrentConsumers is greater than 1,
     * we use {@link SoroushBotMultiThreadConsumer} that use a thread pool in order to process exchanges.
     * the consumer use multiple queue to ensure that every message from a same user
     * goes to the same thread and therefore every message from the same user will be processed in the order of arrival time.
     * <p>
     * if concurrentConsumers is lower than 2 then we use {@link SoroushBotSingleThreadConsumer} that process all received message
     * in the order of their arrival time.
     *
     * @param processor processor
     * @return consumer
     */
    @Override
    public Consumer createConsumer(Processor processor) throws Exception {
        Consumer consumer;
        if (type != Endpoint.getMessage) {
            throw new IllegalArgumentException("only " + Endpoint.getMessage + " support for consumer(from)");
        }
        if (concurrentConsumers < 2) {
            consumer = new SoroushBotSingleThreadConsumer(this, processor);
            return consumer;
        } else {
            consumer = new SoroushBotMultiThreadConsumer(this, processor);
        }
        //configure consumer using method available by DefaultConsumer
        configureConsumer(consumer);
        return consumer;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    /**
     * create a {@link WebTarget} that could be used to download file from soroush based on {@link SoroushBotEndpoint#authorizationToken},
     * {@link SoroushBotEndpoint#connectionTimeout} and {@code fileUrl} (fileId)
     *
     * @param fileUrl fileId to download
     * @return WebTarget
     */
    private WebTarget getDownloadFileTarget(String fileUrl) {
        return SoroushService.get().createDownloadFileTarget(authorizationToken, fileUrl, connectionTimeout);
    }


    /**
     * return the lazily created instance of {@link SoroushBotEndpoint#uploadFileTarget} to used for uploading file to soroush.
     *
     * @return WebTarget
     */
    private WebTarget getUploadFileTarget() {
        if (uploadFileTarget == null) {
            synchronized (this) {
                if (uploadFileTarget == null) {
                    uploadFileTarget = SoroushService.get().createUploadFileTarget(authorizationToken, connectionTimeout);
                }
            }
        }
        return uploadFileTarget;
    }

    /**
     * return the lazily created instance of {@link SoroushBotEndpoint#sendMessageTarget} to used for sending message to soroush.
     *
     * @return WebTarget
     */
    WebTarget getSendMessageTarget() {
        if (sendMessageTarget == null) {
            synchronized (this) {
                if (sendMessageTarget == null) {
                    sendMessageTarget = SoroushService.get().createSendMessageTarget(authorizationToken, connectionTimeout);
                }
            }
        }
        return sendMessageTarget;
    }

    public Endpoint getType() {
        return type;
    }

    public void setType(Endpoint type) {
        this.type = type;
    }

    public String getAuthorizationToken() {
        return authorizationToken;
    }

    public void setAuthorizationToken(String authorizationToken) {
        this.authorizationToken = authorizationToken;
    }

    public Integer getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Integer connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public Integer getMaxConnectionRetry() {
        return maxConnectionRetry;
    }

    public void setMaxConnectionRetry(Integer maxConnectionRetry) {
        this.maxConnectionRetry = maxConnectionRetry;
    }

    public Integer getConcurrentConsumers() {
        return concurrentConsumers;
    }

    public void setConcurrentConsumers(Integer concurrentConsumers) {
        this.concurrentConsumers = concurrentConsumers;
    }

    public Integer getQueueCapacityPerThread() {
        return queueCapacityPerThread;
    }

    public void setQueueCapacityPerThread(Integer queueCapacityPerThread) {
        this.queueCapacityPerThread = queueCapacityPerThread;
    }

    public Boolean getAutoUploadFile() {
        return autoUploadFile;
    }

    public void setAutoUploadFile(Boolean autoUploadFile) {
        this.autoUploadFile = autoUploadFile;
    }

    public Boolean getForceUpload() {
        return forceUpload;
    }

    public void setForceUpload(Boolean forceUpload) {
        this.forceUpload = forceUpload;
    }

    public Boolean getDownloadThumbnail() {
        return downloadThumbnail;
    }

    public void setDownloadThumbnail(Boolean downloadThumbnail) {
        this.downloadThumbnail = downloadThumbnail;
    }

    public Long getRetryWaitingTime() {
        return retryWaitingTime;
    }

    public void setRetryWaitingTime(Long retryWaitingTime) {
        this.retryWaitingTime = retryWaitingTime;
    }

    public String getBackOffStrategy() {
        return backOffStrategy;
    }

    public void setBackOffStrategy(String backOffStrategy) {
        this.backOffStrategy = backOffStrategy;
    }

    public Long getRetryExponentialCoefficient() {
        return retryExponentialCoefficient;
    }

    public Long getReconnectIdleConnectionTimeout() {
        return reconnectIdleConnectionTimeout;
    }

    public void setReconnectIdleConnectionTimeout(Long reconnectIdleConnectionTimeout) {
        this.reconnectIdleConnectionTimeout = reconnectIdleConnectionTimeout;
    }

    public void setRetryExponentialCoefficient(Long retryExponentialCoefficient) {
        this.retryExponentialCoefficient = retryExponentialCoefficient;
    }

    public Long getRetryLinearIncrement() {
        return retryLinearIncrement;
    }

    public void setRetryLinearIncrement(Long retryLinearIncrement) {
        this.retryLinearIncrement = retryLinearIncrement;
    }

    public Long getMaxRetryWaitingTime() {
        return maxRetryWaitingTime;
    }

    public void setMaxRetryWaitingTime(Long maxRetryWaitingTime) {
        this.maxRetryWaitingTime = maxRetryWaitingTime;
    }

    public Boolean getForceDownload() {
        return forceDownload;
    }

    public void setForceDownload(Boolean forceDownload) {
        this.forceDownload = forceDownload;
    }

    public Boolean getAutoDownload() {
        return autoDownload;
    }

    public void setAutoDownload(Boolean autoDownload) {
        this.autoDownload = autoDownload;
    }

    /**
     * try to upload an inputStream to server
     *
     * @param inputStream
     * @param message
     * @param fileType
     */
    private UploadFileResponse uploadToServer(InputStream inputStream, SoroushMessage message, String fileType) throws SoroushException, InterruptedException {
        javax.ws.rs.core.Response response;
        //this for handle connection retry if sending request failed.
        for (int count = 0; count <= maxConnectionRetry; count++) {
            waitBeforeRetry(count);
            MultiPart multipart = new MultiPart();
            multipart.setMediaType(MediaType.MULTIPART_FORM_DATA_TYPE);
            multipart.bodyPart(new StreamDataBodyPart("file", inputStream, null, MediaType.APPLICATION_OCTET_STREAM_TYPE));
            try {
                if (log.isDebugEnabled()) {
                    log.debug("try to upload " + fileType + " for the " + StringUtils.ordinal(count + 1) + " time for message:" + message);
                }
                response = getUploadFileTarget().request(MediaType.APPLICATION_JSON_TYPE)
                        .post(Entity.entity(multipart, multipart.getMediaType()));
                return SoroushService.get().assertSuccessful(response, UploadFileResponse.class, message);
            } catch (IOException | ProcessingException ex) {
                //if maximum connection retry reached, abort sending the request.
                if (count == maxConnectionRetry) {
                    throw new MaximumConnectionRetryReachedException("uploading " + fileType + " for message " + message + " failed. Maximum retry limit reached!"
                            + " aborting upload file and send message", ex, message);
                }
                if (log.isWarnEnabled()) {
                    log.warn("uploading " + fileType + " for message " + message + " failed", ex);
                }
            }
        }
        log.error("should never reach this line of code because maxConnectionRetry is greater than -1 and at least the above for must execute single time and");
        //for backup
        throw new MaximumConnectionRetryReachedException("uploading " + fileType + " for message " + message + " failed. Maximum retry limit reached! aborting "
                + "upload file and send message", message);
    }

    /**
     * check if {@link SoroushMessage#file} or {@link SoroushMessage#thumbnail} is populated and upload them to the server.
     * after that it set {@link SoroushMessage#fileUrl} and {@link SoroushMessage#thumbnailUrl} to appropriate value
     *
     * @param message
     * @throws SoroushException if soroush reject the file
     */
    void handleFileUpload(SoroushMessage message) throws SoroushException, InterruptedException {
        if (log.isTraceEnabled()) {
            log.trace("try to upload file(s) to server if exists for message:" + message.toString());
        }
        InputStream file = message.getFile();
        if (file != null && (message.getFileUrl() == null || forceUpload)) {
            if (log.isDebugEnabled()) {
                log.debug("uploading file to server for message: " + message);
            }
            UploadFileResponse response = uploadToServer(file, message, "file");
            message.setFileUrl(response.getFileUrl());
            if (log.isDebugEnabled()) {
                log.debug("uploaded file url is: " + response.getFileUrl() + " for message: " + message);
            }
        }
        InputStream thumbnail = message.getThumbnail();
        if (thumbnail != null && message.getThumbnailUrl() == null) {
            if (log.isDebugEnabled()) {
                log.debug("uploading thumbnail to server for message: " + message);
            }
            UploadFileResponse response = uploadToServer(thumbnail, message, "thumbnail");
            message.setThumbnailUrl(response.getFileUrl());
            if (log.isDebugEnabled()) {
                log.debug("uploaded thumbnail url is: " + response.getFileUrl() + " for message: " + message);
            }
        }
    }

    /**
     * check whether {@link SoroushMessage#fileUrl}({@link SoroushMessage#thumbnailUrl}) is null or not, and download the resource if it is not null
     * this function only set {@link SoroushMessage#file} to {@link InputStream} get from {@link Response#readEntity(Class)} )}
     * and does not store the resource in file.
     *
     * @param message
     * @throws SoroushException if the file does not exists on soroush or soroush reject the request
     */
    public void handleDownloadFiles(SoroushMessage message) throws SoroushException {
        if (message.getFileUrl() != null && (message.getFile() == null || forceDownload)) {
            if (log.isDebugEnabled()) {
                log.debug("downloading file from server for message: " + message);
            }
            InputStream inputStream = downloadFromServer(message.getFileUrl(), message, "file");
            message.setFile(inputStream);
            if (log.isDebugEnabled()) {
                log.debug("file successfully downloaded for message: " + message);
            }
        }
        if (downloadThumbnail && message.getThumbnailUrl() != null && (message.getThumbnail() == null || forceDownload)) {
            if (log.isDebugEnabled()) {
                log.debug("downloading thumbnail from server for message: " + message);
            }
            InputStream inputStream = downloadFromServer(message.getThumbnailUrl(), message, "thumbnail");
            message.setThumbnail(inputStream);
            if (log.isDebugEnabled()) {
                log.debug("thumbnail successfully downloaded for message: " + message);
            }
        }
    }

    /**
     * download the resource stored with the key {@code fileUrl} from Soroush Server.
     * other parameters are used only for logging.
     *
     * @param fileUrl
     * @param message
     * @param type
     * @return
     * @throws SoroushException if soroush reject the request
     */
    private InputStream downloadFromServer(String fileUrl, SoroushMessage message, String type) throws SoroushException {
        Response response = null;
        for (int i = 0; i <= maxConnectionRetry; i++) {
            WebTarget target = getDownloadFileTarget(fileUrl);
            if (log.isDebugEnabled()) {
                if (i != 0) {
                    log.debug("retry downloading " + type + ": " + fileUrl + " for the " + StringUtils.ordinal(i) + " time");
                }
                log.debug("try to download " + type + ": " + fileUrl + " with url: " + target.getUri() + "\nfor message: " + message);
            }
            try {
                response = target.request().get();
                return SoroushService.get().assertSuccessful(response, InputStream.class, message);
            } catch (IOException | ProcessingException ex) {
                if (i == maxConnectionRetry) {
                    throw new MaximumConnectionRetryReachedException("maximum connection retry reached for " + type + ": " + fileUrl, ex, message);
                }
                if (log.isWarnEnabled()) {
                    log.warn("can not download " + type + ": " + fileUrl + " from soroush. Response code is", ex);
                }
            }
        }
        //should never reach this line
        log.error("should never reach this line. An exception should have been thrown by catch block for target.request().get");
        throw new MaximumConnectionRetryReachedException("can not upload " + type + ": " + fileUrl + " response:" + ((response == null) ? null : response.getStatus()), message);
    }

    public void waitBeforeRetry(int retryCount) throws InterruptedException {
        backOffStrategyHelper.waitBeforeRetry(retryCount);
    }
}
