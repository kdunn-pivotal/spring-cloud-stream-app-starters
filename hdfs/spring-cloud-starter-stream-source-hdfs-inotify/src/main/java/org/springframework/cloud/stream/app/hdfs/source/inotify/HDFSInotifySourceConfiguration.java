/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.cloud.stream.app.hdfs.source.inotify;

import java.net.URI;
import java.net.URISyntaxException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.app.annotation.PollableSource;
import org.springframework.cloud.stream.app.trigger.TriggerConfiguration;
import org.springframework.cloud.stream.app.trigger.TriggerProperties;
import org.springframework.cloud.stream.app.trigger.TriggerPropertiesMaxMessagesDefaultOne;
import org.springframework.cloud.stream.messaging.Source;
import org.springframework.context.annotation.Import;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSInotifyEventInputStream;
import org.apache.hadoop.hdfs.client.HdfsAdmin;
import org.apache.hadoop.hdfs.inotify.Event;
import org.apache.hadoop.hdfs.inotify.EventBatch;
import org.apache.hadoop.hdfs.inotify.MissingEventsException;

/**
 * @author Kyle Dunn
 * 
 * This processor polls the notification events provided by the HdfsAdmin API. 
 * Since this uses the HdfsAdmin APIs it is required to run as an HDFS super user. 
 * Currently there are six types of events (append, close, create, metadata, rename, and unlink). 
 * Please see org.apache.hadoop.hdfs.inotify.Event documentation for full explanations of each event. 
 * This processor will poll for new events based on a defined duration. It is also important to be 
 * aware that this processor must consume all events. This is because the HDFS admin's event notifications 
 * API does not have filtering. 
 */
@EnableBinding(Source.class)
@EnableConfigurationProperties(HDFSInotifySourceConfiguration.class)
@Import({TriggerConfiguration.class, TriggerPropertiesMaxMessagesDefaultOne.class})
public class HDFSInotifySourceConfiguration {
	
    @Autowired
    private HDFSInotifySourceProperties properties;

    private static Log logger = LogFactory.getLog(HDFSInotifySourceConfiguration.class);

    private volatile long lastTxId = -1L;
    
    private NotificationConfig notificationConfig;
    
    private HdfsAdmin hdfsAdmin;

	@Autowired
	private TriggerProperties triggerProperties;

	@Autowired
    public void setNotificationConfig() {
		this.notificationConfig = new NotificationConfig(this.properties.getHdfsPathToWatch(), this.properties.getIgnoreHiddenFiles());
    }
	
    @Autowired
    protected void setHdfsAdmin() throws IOException, URISyntaxException {
        this.hdfsAdmin = new HdfsAdmin(new URI(this.properties.getHdfsUri()), new Configuration());
    }
	
    private MessageChannel output;

    @Autowired
    public void SendingBean(MessageChannel output) {
        this.output = output;
    }
    
    @PollableSource
    public void readEvents() {
        try {
        	DFSInotifyEventInputStream eventStream;
        	if (lastTxId == -1L) {
        		eventStream = hdfsAdmin.getInotifyEventStream();
        	}
        	else {
        		eventStream = hdfsAdmin.getInotifyEventStream(lastTxId);
        	}
            
        	EventBatch eventBatch = getEventBatch(eventStream);
            lastTxId = eventBatch.getTxid();
            
            if (eventBatch != null && eventBatch.getEvents() != null) {
                for (Event e : eventBatch.getEvents()) {
                    if (toProcessEvent(e)) {
                    	String thisEvent = e.getEventType().name() + "," + getPath(e);
                    	output.send(MessageBuilder.withPayload(thisEvent).build());
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
        	logger.error("Unable to get notification information: {}", e);;
        } catch (MissingEventsException e) {
            // set lastTxId to -1 and update state. This may cause events not to be processed. The reason this exception is thrown is described in the
            // org.apache.hadoop.hdfs.client.HdfsAdmin#getInotifyEventStrea API. It suggests tuning a couple parameters if this API is used.
            lastTxId = -1L;
            logger.error("Unable to get notification information. Setting transaction id to -1. This may cause some events to get missed. " +
                    "Please see javadoc for org.apache.hadoop.hdfs.client.HdfsAdmin#getInotifyEventStream: {}", e);
        }
        return;
    }

    private EventBatch getEventBatch(DFSInotifyEventInputStream eventStream) throws IOException, InterruptedException, MissingEventsException {
        // According to the inotify API we should retry a few times if poll throws an IOException.
        // Please see org.apache.hadoop.hdfs.DFSInotifyEventInputStream#poll for documentation.
    	
        final TimeUnit pollDurationTimeUnit = TimeUnit.SECONDS;
        final long pollDuration = this.properties.getPollDuration();
    	
        int i = 0;
        while (true) {
            try {
                i += 1;
                return eventStream.poll(pollDuration, pollDurationTimeUnit);
            } catch (IOException e) {
                if (i > this.properties.getNumPollRetries()) {
                	logger.debug("Failed to poll for event batch. Reached max retry times.", e);
                    throw e;
                } else {
                	logger.debug("Attempt " + i + " failed to poll for event batch. Retrying.");
                }
            }
        }
    }

    private boolean toProcessEvent(Event event) {
        final String[] eventTypes = this.properties.getEventTypes().split(",");
        for (String name : eventTypes) {
            if (name.trim().equalsIgnoreCase(event.getEventType().name())) {
                return notificationConfig.getPathFilter().accept(new Path(getPath(event)));
            }
        }

        return false;
    }

    private String getPath(Event event) {
        if (event == null || event.getEventType() == null) {
            throw new IllegalArgumentException("Event and event type must not be null.");
        }

        switch (event.getEventType()) {
            case CREATE: return ((Event.CreateEvent) event).getPath();
            case CLOSE: return ((Event.CloseEvent) event).getPath();
            case APPEND: return ((Event.AppendEvent) event).getPath();
            case RENAME: return ((Event.RenameEvent) event).getSrcPath();
            case METADATA: return ((Event.MetadataUpdateEvent) event).getPath();
            case UNLINK: return ((Event.UnlinkEvent) event).getPath();
            default: throw new IllegalArgumentException("Unsupported event type.");
        }
    }

    private static class NotificationConfig {
        private final PathFilter pathFilter;

        NotificationConfig(String hdfsPathToWatch, boolean ignoreHiddenFiles) {
        	// read some class members, set a filter
        	
            final Pattern watchDirectory = Pattern.compile(hdfsPathToWatch);
            pathFilter = new NotificationEventPathFilter(watchDirectory, ignoreHiddenFiles);
        }

        PathFilter getPathFilter() {
            return pathFilter;
        }
    }
    
    /*
     * Take single events as they become available, rather than in batch mode
     * http://johnjianfang.blogspot.com/2015/03/hdfs-6634-inotify-in-hdfs.html?m=1
     * http://stackoverflow.com/questions/29960186/hdfs-file-watcher
     */
    /*
     public static void main( String[] args ) throws IOException, InterruptedException, MissingEventsException
	{
	    HdfsAdmin admin = new HdfsAdmin( URI.create( args[0] ), new Configuration() );
	    DFSInotifyEventInputStream eventStream = admin.getInotifyEventStream();
	    while( true ) {
	        EventBatch events = eventStream.take();
	        for( Event event : events.getEvents() ) {
	            System.out.println( "event type = " + event.getEventType() );
	            switch( event.getEventType() ) {
	                case CREATE:
	                    CreateEvent createEvent = (CreateEvent) event;
	                    System.out.println( "  path = " + createEvent.getPath() );
	                    break;
	                default:
	                    break;
	            }
	        }
	    }
	}
     */
    
}

