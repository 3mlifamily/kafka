/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.clients.tools;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import org.json.simple.JSONObject;

import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static net.sourceforge.argparse4j.impl.Arguments.store;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

/**
 * Primarily intended for use with system testing, this producer prints metadata
 * in the form of JSON to stdout on each "send" request. For example, this helps
 * with end-to-end correctness tests by making externally visible which messages have been
 * acked and which have not.
 *
 * When used as a command-line tool, it produces increasing integers. It will produce a 
 * fixed number of messages unless the default max-messages -1 is used, in which case
 * it produces indefinitely.
 *  
 * If logging is left enabled, log output on stdout can be easily ignored by checking
 * whether a given line is valid JSON.
 */
public class VerifiableProducer {
    
    String topic;
    private Producer<String, String> producer;
    // If maxMessages < 0, produce until the process is killed externally
    private long maxMessages = -1;
    
    // Number of messages for which acks were received
    private long numAcked = 0;
    
    // Number of send attempts
    private long numSent = 0;
    
    // Throttle message throughput if this is set >= 0
    private long throughput;
    
    // Hook to trigger producing thread to stop sending messages
    private boolean stopProducing = false;
    
    // Timeout on producer.close() call
    private long closeTimeoutSeconds;

    public VerifiableProducer(
            Properties producerProps, String topic, int throughput, int maxMessages, long closeTimeoutSeconds) {

        this.topic = topic;
        this.throughput = throughput;
        this.maxMessages = maxMessages;
        this.closeTimeoutSeconds = closeTimeoutSeconds;
        this.producer = new KafkaProducer<String, String>(producerProps);
    }

    /** Get the command-line argument parser. */
    private static ArgumentParser argParser() {
        ArgumentParser parser = ArgumentParsers
                .newArgumentParser("verifiable-producer")
                .defaultHelp(true)
                .description("This tool produces increasing integers to the specified topic and prints JSON metadata to stdout on each \"send\" request, making externally visible which messages have been acked and which have not.");

        parser.addArgument("--topic")
                .action(store())
                .required(true)
                .type(String.class)
                .metavar("TOPIC")
                .help("Produce messages to this topic.");

        parser.addArgument("--broker-list")
                .action(store())
                .required(true)
                .type(String.class)
                .metavar("HOST1:PORT1[,HOST2:PORT2[...]]")
                .dest("brokerList")
                .help("Comma-separated list of Kafka brokers in the form HOST1:PORT1,HOST2:PORT2,...");
        
        parser.addArgument("--max-messages")
                .action(store())
                .required(false)
                .setDefault(-1)
                .type(Integer.class)
                .metavar("MAX-MESSAGES")
                .dest("maxMessages")
                .help("Produce this many messages. If -1, produce messages until the process is killed externally.");

        parser.addArgument("--throughput")
                .action(store())
                .required(false)
                .setDefault(-1)
                .type(Integer.class)
                .metavar("THROUGHPUT")
                .help("If set >= 0, throttle maximum message throughput to *approximately* THROUGHPUT messages/sec.");

        parser.addArgument("--acks")
                .action(store())
                .required(false)
                .setDefault(-1)
                .type(Integer.class)
                .choices(0, 1, -1)
                .metavar("ACKS")
                .help("Acks required on each produced message. See Kafka docs on request.required.acks for details.");

        parser.addArgument("--close-timeout")
                .action(store())
                .required(false)
                .setDefault(10L)
                .type(Long.class)
                .metavar("CLOSE-TIMEOUT")
                .dest("closeTimeoutSeconds")
                .help("When SIGTERM is caught, wait at most this many seconds for unsent messages to flush before stopping the VerifiableProducer process.");

        return parser;
    }
  
    /** Construct a VerifiableProducer object from command-line arguments. */
    public static VerifiableProducer createFromArgs(String[] args) {
        ArgumentParser parser = argParser();
        VerifiableProducer producer = null;
        
        try {
            Namespace res = parser.parseArgs(args);
            int maxMessages = res.getInt("maxMessages");
            String topic = res.getString("topic");
            int throughput = res.getInt("throughput");
            long closeTimeoutSeconds = res.getLong("closeTimeoutSeconds");

            Properties producerProps = new Properties();
            producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, res.getString("brokerList"));
            producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                              "org.apache.kafka.common.serialization.StringSerializer");
            producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                              "org.apache.kafka.common.serialization.StringSerializer");
            producerProps.put(ProducerConfig.ACKS_CONFIG, Integer.toString(res.getInt("acks")));
            // No producer retries
            producerProps.put("retries", "0");

            producer = new VerifiableProducer(producerProps, topic, throughput, maxMessages, closeTimeoutSeconds);
        } catch (ArgumentParserException e) {
            if (args.length == 0) {
                parser.printHelp();
                System.exit(0);
            } else {
                parser.handleError(e);
                System.exit(1);
            }
        }
        
        return producer;
    }
  
    /** Produce a message with given key and value. */
    public void send(String key, String value) {
        ProducerRecord<String, String> record = new ProducerRecord<String, String>(topic, key, value);
        numSent++;
        try {
            producer.send(record, new PrintInfoCallback(key, value));
        } catch (Exception e) {

            synchronized (System.out) {
                System.out.println(errorString(e, key, value, System.currentTimeMillis()));
            }
        }
    }
  
    /** Close the producer to flush any remaining messages. */
    public void close() {
        producer.close(this.closeTimeoutSeconds, TimeUnit.SECONDS);
    }
  
    /**
     * Return JSON string encapsulating basic information about the exception, as well
     * as the key and value which triggered the exception.
     */
    String errorString(Exception e, String key, String value, Long nowMs) {
        assert e != null : "Expected non-null exception.";
    
        JSONObject obj = new JSONObject();
        obj.put("class", this.getClass().toString());
        obj.put("name", "producer_send_error");
        
        obj.put("time_ms", nowMs);
        obj.put("exception", e.getClass().toString());
        obj.put("message", e.getMessage());
        obj.put("topic", this.topic);
        obj.put("key", key);
        obj.put("value", value);
        return obj.toJSONString();
    }
  
    String successString(RecordMetadata recordMetadata, String key, String value, Long nowMs) {
        assert recordMetadata != null : "Expected non-null recordMetadata object.";
    
        JSONObject obj = new JSONObject();
        obj.put("class", this.getClass().toString());
        obj.put("name", "producer_send_success");
        
        obj.put("time_ms", nowMs);
        obj.put("topic", this.topic);
        obj.put("partition", recordMetadata.partition());
        obj.put("offset", recordMetadata.offset());
        obj.put("key", key);
        obj.put("value", value);
        return obj.toJSONString();
    }
  
    /** Callback which prints errors to stdout when the producer fails to send. */
    private class PrintInfoCallback implements Callback {
        
        private String key;
        private String value;
    
        PrintInfoCallback(String key, String value) {
            this.key = key;
            this.value = value;
        }
    
        public void onCompletion(RecordMetadata recordMetadata, Exception e) {
            synchronized (System.out) {
                if (e == null) {
                    VerifiableProducer.this.numAcked++;
                    System.out.println(successString(recordMetadata, this.key, this.value, System.currentTimeMillis()));
                } else {
                    System.out.println(errorString(e, this.key, this.value, System.currentTimeMillis()));
                }
            }
        }
    }
  
    public static void main(String[] args) throws IOException {
        
        final VerifiableProducer producer = createFromArgs(args);
        final long startMs = System.currentTimeMillis();
        boolean infinite = producer.maxMessages < 0;

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // Trigger main thread to stop producing messages
                producer.stopProducing = true;
                
                // Flush any remaining messages
                producer.close();

                // Print a summary
                long stopMs = System.currentTimeMillis();
                double avgThroughput = 1000 * ((producer.numAcked) / (double) (stopMs - startMs));
                JSONObject obj = new JSONObject();
                obj.put("class", producer.getClass().toString());
                obj.put("name", "tool_data");
                obj.put("sent", producer.numSent);
                obj.put("acked", producer.numAcked);
                obj.put("target_throughput", producer.throughput);
                obj.put("avg_throughput", avgThroughput);
                System.out.println(obj.toJSONString());
            }
        });

        ThroughputThrottler throttler = new ThroughputThrottler(producer.throughput, startMs);
        for (int i = 0; i < producer.maxMessages || infinite; i++) {
            if (producer.stopProducing) {
                break;
            }
            long sendStartMs = System.currentTimeMillis();
            producer.send(null, String.format("%d", i));
            
            if (throttler.shouldThrottle(i, sendStartMs)) {
                throttler.throttle();
            }
        }
    }
        
}
