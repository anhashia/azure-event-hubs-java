/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */
package com.microsoft.azure.eventhubs;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ActiveClientTokenManager {

    private static final Logger TRACE_LOGGER = LoggerFactory.getLogger(ActiveClientTokenManager.class);

    private ScheduledFuture timer;

    private final Object timerLock;
    private final Runnable sendTokenTask;
    private final ClientEntity clientEntity;
    private final Duration tokenRefreshInterval;

    public ActiveClientTokenManager(
            final ClientEntity clientEntity,
            final Runnable sendTokenAsync,
            final Duration tokenRefreshInterval) {

        this.sendTokenTask = sendTokenAsync;
        this.clientEntity = clientEntity;
        this.tokenRefreshInterval = tokenRefreshInterval;
        this.timerLock = new Object();

        synchronized (this.timerLock) {
            this.timer = Timer.schedule(new TimerCallback(), tokenRefreshInterval, TimerType.OneTimeRun);
        }
    }

    public void cancel() {

        synchronized (this.timerLock) {
            this.timer.cancel(false);
        }
    }

    private class TimerCallback implements Runnable {

        @Override
        public void run() {

            if (!clientEntity.getIsClosingOrClosed()) {

                sendTokenTask.run();

                synchronized (timerLock) {
                    timer = Timer.schedule(new TimerCallback(), tokenRefreshInterval, TimerType.OneTimeRun);
                }
            } else {

                if (TRACE_LOGGER.isInfoEnabled()) {
                    TRACE_LOGGER.info(
                            String.format(Locale.US,
                                    "clientEntity[%s] - closing ActiveClientLinkManager", clientEntity.getClientId()));
                }
            }
        }

    }
}
