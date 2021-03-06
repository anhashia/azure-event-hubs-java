/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package com.microsoft.azure.eventprocessorhost;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import com.microsoft.azure.eventhubs.EventData;
import com.microsoft.azure.eventhubs.EventHubClient;
import com.microsoft.azure.eventhubs.PartitionReceiveHandler;
import com.microsoft.azure.eventhubs.PartitionReceiver;
import com.microsoft.azure.eventhubs.ReceiverOptions;
import com.microsoft.azure.eventhubs.ReceiverDisconnectedException;
import com.microsoft.azure.eventhubs.EventHubException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class EventHubPartitionPump extends PartitionPump
{
    private CompletableFuture<?> internalOperationFuture = null;
    
	private EventHubClient eventHubClient = null;
	private PartitionReceiver partitionReceiver = null;
    private InternalReceiveHandler internalReceiveHandler = null;

    private static final Logger TRACE_LOGGER = LoggerFactory.getLogger(EventHubPartitionPump.class);

	EventHubPartitionPump(EventProcessorHost host, Pump pump, Lease lease)
	{
		super(host, pump, lease);
	}

    @Override
    void specializedStartPump()
    {
    	boolean openedOK = false;
    	int retryCount = 0;
    	Exception lastException = null;
    	do
    	{
	        try
	        {
				openClients();
				openedOK = true;
			}
	        catch (Exception e)
	        {
	        	lastException = e;
	        	if ((e instanceof ExecutionException) && (e.getCause() instanceof ReceiverDisconnectedException))
	        	{
	        		// TODO Assuming this is due to a receiver with a higher epoch.
	        		// Is there a way to be sure without checking the exception text?
                    TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(this.host.getHostName(), this.partitionContext,
                            "Receiver disconnected on create, bad epoch?"), e);
	        		// If it's a bad epoch, then retrying isn't going to help.
	        		break;
	        	}
	        	else
	        	{
					TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(this.host.getHostName(), this.partitionContext,
                            "Failure creating client or receiver, retrying"), e);
					retryCount++;
	        	}
			}
    	} while (!openedOK && (retryCount < 5));
    	if (!openedOK)
    	{
            // IEventProcessor.onOpen is called from the base PartitionPump and must have returned in order for execution to reach here, 
    		// so we can report this error to it instead of the general error handler.
    		this.processor.onError(this.partitionContext, lastException);
			this.pumpStatus = PartitionPumpStatus.PP_OPENFAILED;
    	}

        if (this.pumpStatus == PartitionPumpStatus.PP_OPENING)
        {
            this.internalReceiveHandler = new InternalReceiveHandler();
            // IEventProcessor.onOpen is called from the base PartitionPump and must have returned in order for execution to reach here, 
            // meaning it is safe to set the handler and start calling IEventProcessor.onEvents.
            // Set the status to running before setting the javaClient handler, so the IEventProcessor.onEvents can never race and see status != running.
            this.pumpStatus = PartitionPumpStatus.PP_RUNNING;
            this.partitionReceiver.setReceiveHandler(this.internalReceiveHandler, this.host.getEventProcessorOptions().getInvokeProcessorAfterReceiveTimeout());
        }
        
        if (this.pumpStatus == PartitionPumpStatus.PP_OPENFAILED)
        {
        	this.pumpStatus = PartitionPumpStatus.PP_CLOSING;
        	cleanUpClients();
        	this.pumpStatus = PartitionPumpStatus.PP_CLOSED;
        }
    }
    
    private void openClients() throws EventHubException, IOException, InterruptedException, ExecutionException
    {
    	// Create new client
        TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), this.partitionContext, "Opening EH client"));
		this.internalOperationFuture = EventHubClient.createFromConnectionString(this.host.getEventHubConnectionString());
		this.eventHubClient = (EventHubClient) this.internalOperationFuture.get();
		this.internalOperationFuture = null;
		
	// Create new receiver and set options
        ReceiverOptions options = new ReceiverOptions();
        options.setReceiverRuntimeMetricEnabled(this.host.getEventProcessorOptions().getReceiverRuntimeMetricEnabled());
    	Object startAt = this.partitionContext.getInitialOffset();
    	long epoch = this.lease.getEpoch();
        TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), this.partitionContext,
                "Opening EH receiver with epoch " + epoch + " at location " + startAt));
    	if (startAt instanceof String)
    	{
    		this.internalOperationFuture = this.eventHubClient.createEpochReceiver(this.partitionContext.getConsumerGroupName(), this.partitionContext.getPartitionId(),
    				(String)startAt, epoch, options);
    	}
    	else if (startAt instanceof Instant) 
    	{
    		this.internalOperationFuture = this.eventHubClient.createEpochReceiver(this.partitionContext.getConsumerGroupName(), this.partitionContext.getPartitionId(),
    				(Instant)startAt, epoch, options);
    	}
    	else
    	{
    		String errMsg = "Starting offset is not String or Instant, is " + ((startAt != null) ? startAt.getClass().toString() : "null");
            TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(this.host.getHostName(), this.partitionContext, errMsg));
    		throw new RuntimeException(errMsg);
    	}
		this.lease.setEpoch(epoch);
		if (this.internalOperationFuture != null)
		{
			this.partitionReceiver = (PartitionReceiver) this.internalOperationFuture.get();
		}
		else
		{
            TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(this.host.getHostName(), this.partitionContext,
                    "createEpochReceiver failed with null"));
			throw new RuntimeException("createEpochReceiver failed with null");
		}
		this.partitionReceiver.setPrefetchCount(this.host.getEventProcessorOptions().getPrefetchCount());
		this.partitionReceiver.setReceiveTimeout(this.host.getEventProcessorOptions().getReceiveTimeOut());
		this.internalOperationFuture = null;

        TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), this.partitionContext,
                "EH client and receiver creation finished"));
    }
    
    private void cleanUpClients() // swallows all exceptions
    {
        if (this.partitionReceiver != null)
        {
			// Disconnect the processor from the receiver we're about to close.
			// Fortunately this is idempotent -- setting the handler to null when it's already been
			// nulled by code elsewhere is harmless!
			// Setting to null also waits for the in-progress calls to complete
            TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), this.partitionContext,
                    "Setting receive handler to null"));
			try
			{
				this.partitionReceiver.setReceiveHandler(null).get();
			}
			catch (InterruptedException | ExecutionException exception)
			{
				if (exception instanceof InterruptedException)
				{
					// Re-assert the thread's interrupted status
					Thread.currentThread().interrupt();
				}

				final Throwable throwable = exception.getCause();
				if (throwable != null)
				{
					TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), this.partitionContext,
							"Got exception from onEvents when ReceiveHandler is set to null."), throwable);
				}
			}

            TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), this.partitionContext, "Closing EH receiver"));
        	final PartitionReceiver partitionReceiverTemp = this.partitionReceiver;
			this.partitionReceiver = null;

			try
			{
				partitionReceiverTemp.closeSync();
			}
			catch (EventHubException exception)
			{
                TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(this.host.getHostName(), this.partitionContext,
                        "Closing EH receiver failed."), exception);
			}
        }
        else
        {
            TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), this.partitionContext,
                    "partitionReceiver is null in cleanup"));
        }

        if (this.eventHubClient != null)
		{
            TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), this.partitionContext, "Closing EH client"));
			final EventHubClient eventHubClientTemp = this.eventHubClient;
			this.eventHubClient = null;

			try
			{
				eventHubClientTemp.closeSync();
			}
			catch (EventHubException exception)
			{
				TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), this.partitionContext, "Closing EH client failed."), exception);
			}
		}
        else
        {
            TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), this.partitionContext,
                    "eventHubClient is null in cleanup"));
        }
    }

    @Override
    void specializedShutdown(CloseReason reason)
    {
    	// If an open operation is stuck, this lets us shut down anyway.
    	CompletableFuture<?> captured = this.internalOperationFuture;
    	if (captured != null)
    	{
    		captured.cancel(true);
    	}
    	
    	if (this.partitionReceiver != null)
    	{
    		// Close the EH clients. Errors are swallowed, nothing we could do about them anyway.
            cleanUpClients();
    	}
    }
    
    
    private class InternalReceiveHandler extends PartitionReceiveHandler
    {
    	InternalReceiveHandler()
    	{
    		super(EventHubPartitionPump.this.host.getEventProcessorOptions().getMaxBatchSize());
    	}
    	
		@Override
		public void onReceive(Iterable<EventData> events)
		{
                    if (EventHubPartitionPump.this.host.getEventProcessorOptions().getReceiverRuntimeMetricEnabled())
                    {
                        EventHubPartitionPump.this.partitionContext.setRuntimeInformation(EventHubPartitionPump.this.partitionReceiver.getRuntimeInformation());
                    }
                    
                    // This method is called on the thread that the Java EH client uses to run the pump.
                    // There is one pump per EventHubClient. Since each PartitionPump creates a new EventHubClient,
                    // using that thread to call onEvents does no harm. Even if onEvents is slow, the pump will
                    // get control back each time onEvents returns, and be able to receive a new batch of messages
                    // with which to make the next onEvents call. The pump gains nothing by running faster than onEvents.

                    // The underlying client returns null if there are no events, but the contract for IEventProcessor
                    // is different and is expecting an empty iterable if there are no events (and invoke processor after
                    // receive timeout is turned on).
                    
                    Iterable<EventData> effectiveEvents = events;
                    if (effectiveEvents == null)
                    {
                    	effectiveEvents = new ArrayList<EventData>();
                    }
                    
                    EventHubPartitionPump.this.onEvents(effectiveEvents);
		}

		@Override
		public void onError(Throwable error)
		{
			EventHubPartitionPump.this.pumpStatus = PartitionPumpStatus.PP_ERRORED;
			if (error == null)
			{
				error = new Throwable("No error info supplied by EventHub client");
			}
			if (error instanceof ReceiverDisconnectedException)
			{
				TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(EventHubPartitionPump.this.host.getHostName(),
                        EventHubPartitionPump.this.partitionContext,
						"EventHub client disconnected, probably another host took the partition"));
			}
			else
			{
                TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(EventHubPartitionPump.this.host.getHostName(),
                        EventHubPartitionPump.this.partitionContext, "EventHub client error: " + error.toString()));
				if (error instanceof Exception)
				{
					TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(EventHubPartitionPump.this.host.getHostName(),
                            EventHubPartitionPump.this.partitionContext, "EventHub client error continued"), (Exception)error);
				}
			}

			// It is vital to perform the rest of cleanup in a separate thread and not block this one. This thread is the client's
			// receive pump thread, and blocking it means that the receive pump never completes its CompletableFuture, which in turn
			// blocks other client calls that we would like to make during cleanup. Specifically, this issue was found when
			// PartitionReceiver.setReceiveHandler(null).get() was called and never returned.
			final Throwable capturedError = error;
			EventHubPartitionPump.this.host.getExecutorService().submit(() -> EventHubPartitionPump.this.onError(capturedError));
		}
    }
}
