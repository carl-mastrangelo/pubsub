/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.pubsub;

import com.google.auth.Credentials;
import com.google.cloud.pubsub.Subscriber.MessageReceiver;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.GetSubscriptionRequest;
import com.google.pubsub.v1.ModifyAckDeadlineRequest;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.SubscriberGrpc;
import com.google.pubsub.v1.SubscriberGrpc.SubscriberFutureStub;
import com.google.pubsub.v1.Subscription;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import io.grpc.auth.MoreCallCredentials;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link AbstractSubscriberConnection} based on Cloud Pub/Sub pull and
 * acknowledge operations.
 */
final class PollingSubscriberConnection extends AbstractSubscriberConnection {
  private static final int MAX_PER_REQUEST_CHANGES = 1000;
  private static final Duration DEFAULT_TIMEOUT = Duration.standardSeconds(10);
  private static final int DEFAULT_MAX_MESSAGES = 1000;
  private static final Duration INITIAL_BACKOFF = Duration.millis(100); // 100ms
  private static final Duration MAX_BACKOFF = Duration.standardSeconds(10); // 10s

  private static final Logger logger = LoggerFactory.getLogger(PollingSubscriberConnection.class);

  private final SubscriberFutureStub stub;

  public PollingSubscriberConnection(
      String subscription,
      Credentials credentials,
      MessageReceiver receiver,
      Duration ackExpirationPadding,
      Distribution ackLatencyDistribution,
      Channel channel,
      FlowController flowController,
      ScheduledExecutorService executor) {
    super(
        subscription,
        receiver,
        ackExpirationPadding,
        ackLatencyDistribution,
        flowController,
        executor);
    stub =
        SubscriberGrpc.newFutureStub(channel)
            .withCallCredentials(MoreCallCredentials.from(credentials));
  }

  @Override
  void initialize() {
    ListenableFuture<Subscription> subscriptionInfo =
        stub.withDeadlineAfter(DEFAULT_TIMEOUT.getMillis(), TimeUnit.MILLISECONDS)
            .getSubscription(
                GetSubscriptionRequest.newBuilder().setSubscription(subscription).build());
    
    Futures.addCallback(
        subscriptionInfo,
        new FutureCallback<Subscription>() {
          @Override
          public void onSuccess(Subscription result) {
            setMessageDeadlineSeconds(result.getAckDeadlineSeconds());
            pullMessages(INITIAL_BACKOFF);
          }

          @Override
          public void onFailure(Throwable cause) {
            notifyFailed(cause);
          }
        });
  }

  private void pullMessages(final Duration backoff) {
    ListenableFuture<PullResponse> pullResult =
        stub.withDeadlineAfter(DEFAULT_TIMEOUT.getMillis(), TimeUnit.MILLISECONDS)
            .pull(
                PullRequest.newBuilder()
                    .setSubscription(subscription)
                    .setMaxMessages(DEFAULT_MAX_MESSAGES)
                    .setReturnImmediately(true)
                    .build());

    Futures.addCallback(
        pullResult,
        new FutureCallback<PullResponse>() {
          @Override
          public void onSuccess(PullResponse pullResponse) {
            processReceivedMessages(pullResponse.getReceivedMessagesList());
            if (pullResponse.getReceivedMessagesCount() == 0) {
              // No messages in response, possibly caught up in backlog, we backoff to avoid 
              // slamming the server.
              executor.schedule(
                  new Runnable() {
                    @Override
                    public void run() {
                      Duration newBackoff = backoff.multipliedBy(2);
                      if (newBackoff.isLongerThan(MAX_BACKOFF)) {
                        newBackoff = MAX_BACKOFF;
                      }
                      pullMessages(newBackoff);
                    }
                  },
                  backoff.getMillis(),
                  TimeUnit.MILLISECONDS);
              return;
            }
            pullMessages(INITIAL_BACKOFF);
          }

          @Override
          public void onFailure(Throwable cause) {
            if (!(cause instanceof StatusRuntimeException)
                || isRetryable(((StatusRuntimeException) cause).getStatus())) {
              logger.error("Failed to pull messages (recoverable): " + cause.getMessage(), cause);
              executor.schedule(
                  new Runnable() {
                    @Override
                    public void run() {
                      Duration newBackoff = backoff.multipliedBy(2);
                      if (newBackoff.isLongerThan(MAX_BACKOFF)) {
                        newBackoff = MAX_BACKOFF;
                      }
                      pullMessages(newBackoff);
                    }
                  },
                  backoff.getMillis(),
                  TimeUnit.MILLISECONDS);
              return;
            }
            notifyFailed(cause);
          }
        });
  }

  @Override
  void sendAckOperations(
      List<String> acksToSend, List<PendingModifyAckDeadline> ackDeadlineExtensions) {
    // Send the modify ack deadlines in batches as not to exceed the max request
    // size.
    List<List<PendingModifyAckDeadline>> modifyAckDeadlineChunks =
        Lists.partition(ackDeadlineExtensions, MAX_PER_REQUEST_CHANGES);
    for (List<PendingModifyAckDeadline> modAckChunk : modifyAckDeadlineChunks) {
      for (PendingModifyAckDeadline modifyAckDeadline : modAckChunk) {
        stub.withDeadlineAfter(DEFAULT_TIMEOUT.getMillis(), TimeUnit.MILLISECONDS)
            .modifyAckDeadline(
                ModifyAckDeadlineRequest.newBuilder()
                    .setSubscription(subscription)
                    .addAllAckIds(modifyAckDeadline.ackIds)
                    .setAckDeadlineSeconds(modifyAckDeadline.deadlineExtensionSeconds)
                    .build());
      }
    }

    List<List<String>> ackChunks = Lists.partition(acksToSend, MAX_PER_REQUEST_CHANGES);
    Iterator<List<String>> ackChunksIt = ackChunks.iterator();
    while (ackChunksIt.hasNext()) {
      List<String> ackChunk = ackChunksIt.next();
      stub.withDeadlineAfter(DEFAULT_TIMEOUT.getMillis(), TimeUnit.MILLISECONDS)
          .acknowledge(
              AcknowledgeRequest.newBuilder()
                  .setSubscription(subscription)
                  .addAllAckIds(ackChunk)
                  .build());
    }
  }
}
