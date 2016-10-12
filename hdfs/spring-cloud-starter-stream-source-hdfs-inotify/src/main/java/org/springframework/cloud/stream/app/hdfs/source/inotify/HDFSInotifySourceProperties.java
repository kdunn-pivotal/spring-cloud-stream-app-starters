/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.cloud.stream.app.hdfs.source.inotify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the HDFS inotify source module.
 *
 * @author Kyle Dunn
 */
@ConfigurationProperties("hdfs.inotify")
public class HDFSInotifySourceProperties {

    /**
     * The HDFS URI 
     */
    private String hdfsUri = "/";
	
	/**
	 * The time before the polling method returns with the next batch of events if they exist.
	 */
    private Integer pollDuration = 1;

    /**
     * The HDFS path to get event notifications for
     */
    private String hdfsPathToWatch = "/";

    /**
     * If true and the final component of the path associated with a 
     * given event starts with a '.' then that event will not be processed.
     */
    private boolean ignoreHiddenFiles = true;

    /**
     * A comma-separated list of event types to process. Valid event types are: 
     * append, close, create, metadata, rename, and unlink. Case does not matter.
     */
    private String eventTypes = "append,close,create,metadata,rename,unlink";

    /**
     * According to the HDFS admin API for event polling it is good to retry 
     * at least a few times. This number defines how many times the poll will 
     * be retried if it throws an IOException.
     */
    private Integer numPollRetries = 3;
    
    public void setHdfsUri(String u) {
        this.hdfsUri = u;
    }

    public String getHdfsUri() {
        return hdfsUri;
    } 
    
    public void setPollDuration(Integer d) {
        this.pollDuration = d;
    }

    public Integer getPollDuration() {
        return pollDuration;
    }
    
    public void setNumPollRetries(Integer r) {
        this.numPollRetries = r;
    }

    public Integer getNumPollRetries() {
        return numPollRetries;
    }
    
    public void setIgnoreHiddenFiles(boolean i) {
        this.ignoreHiddenFiles = i;
    }

    public boolean getIgnoreHiddenFiles() {
        return ignoreHiddenFiles;
    }
    
    public void setHdfsPathToWatch(String p) {
        this.hdfsPathToWatch = p;
    }

    public String getHdfsPathToWatch() {
        return hdfsPathToWatch;
    }
    
    public void setEventTypes(String e) {
        this.eventTypes = e;
    }

    public String getEventTypes() {
        return eventTypes;
    }

}