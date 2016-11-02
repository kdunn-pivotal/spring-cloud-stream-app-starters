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
package org.springframework.cloud.stream.app.hdfs.inotify.source;

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
import javax.annotation.PostConstruct;

import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

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
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;

import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.hdfs.DFSInotifyEventInputStream;
import org.apache.hadoop.hdfs.client.HdfsAdmin;
import org.apache.hadoop.hdfs.inotify.Event;
import org.apache.hadoop.hdfs.inotify.EventBatch;
import org.apache.hadoop.hdfs.inotify.MissingEventsException;

/**
 * @author Kyle Dunn
 * 
 * Pollable source for HDFS notification events provided by the HdfsAdmin API; must run as an HDFS super user. 
 * Currently there are six types of events (append, close, create, metadata, rename, and unlink). 
 * See org.apache.hadoop.hdfs.inotify.Event documentation for details. *All* new events are returned
 * from the API call, at an interval based on the pollable source parameters; the event notifications 
 * API does not implement filtering. A single JMX REST call is also necessary to establish the latest event id.
 */
@Configuration
@EnableBinding(Source.class)
@EnableConfigurationProperties(HdfsInotifySourceProperties.class)
@Import({TriggerConfiguration.class, TriggerPropertiesMaxMessagesDefaultOne.class})
public class HdfsInotifySourceConfiguration {

    @Autowired
    private HdfsInotifySourceProperties properties;

    private static Log logger = LogFactory.getLog(HdfsInotifySourceConfiguration.class);

    private volatile long lastTxId = -1L;
    
    private NotificationConfig notificationConfig;
    
    private HdfsAdmin hdfsAdmin;

    @Autowired
    private TriggerProperties triggerProperties;

    @PostConstruct
    public void setNotificationConfig() {
        this.notificationConfig = new NotificationConfig(this.properties.getHdfsPathToWatch(), this.properties.getIgnoreHiddenFiles());
    }

    @PostConstruct
    protected void setHdfsAdmin() throws IOException, URISyntaxException {
        this.hdfsAdmin = new HdfsAdmin(new URI(this.properties.getHdfsUri()), new org.apache.hadoop.conf.Configuration());
    }
    
    @PollableSource
    public String getNextEvent() {
        try {
            DFSInotifyEventInputStream eventStream;
            if (lastTxId == -1L) {
                final String[] namenodes = this.properties.getNamenodeHttpUris().split(",");
                for (String n : namenodes) {
                    final String uri = "http://" + n + "/jmx?qry=Hadoop:service=NameNode,name=FSNamesystem";

                    JSONParser parser = new JSONParser();
                     
                    RestTemplate restTemplate = new RestTemplate();
                    String nsJmx = restTemplate.getForObject(uri, String.class);
                    //logger.info(nsJmx);
                    JSONObject nsJson = (JSONObject) parser.parse(nsJmx);
                    JSONArray jmxBeans = (JSONArray) nsJson.get("beans");

                    for (Object b : jmxBeans) {
                        JSONObject obj = (JSONObject) b;
                        lastTxId = (long) obj.get("LastWrittenTransactionId");
                    }

                    // No need to query the other Namenode if lastTxId is valid
                    if (lastTxId != -1L) {
                        break;
                    }
                }
                logger.info("Starting from TxId: " + lastTxId);
            }
            eventStream = hdfsAdmin.getInotifyEventStream(lastTxId);
    
            if (eventStream == null) {
                logger.info("Received a null event stream");
                return null; 
            }
            
            EventBatch eventBatch = eventStream.take();

            if (eventBatch == null) {
                logger.info("Received a null event batch");
                return null;
            }

            lastTxId = eventBatch.getTxid();
            
            if (eventBatch != null && eventBatch.getEvents() != null) {
                for (Event e : eventBatch.getEvents()) {
                    if (toProcessEvent(e)) {
                        logger.debug("Adding event " + e.getEventType().name() + " for " + getPath(e));
                        logger.debug("txid : " + lastTxId);
                        String thisEvent = e.getEventType().name() + "," + getPath(e);
                        return thisEvent;
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
        } catch (ParseException e) {
            logger.error("Unable to parse JMX response: {}", e);
        }
        return null;
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
            final Pattern watchDirectory = Pattern.compile(hdfsPathToWatch);
            pathFilter = new NotificationEventPathFilter(watchDirectory, ignoreHiddenFiles);
        }

        PathFilter getPathFilter() {
            return pathFilter;
        }
    }
}

