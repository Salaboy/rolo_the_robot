/**
 * Copyright 2010 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.salaboy.rolo.server;

import com.salaboy.rolo.body.api.Robot;
import com.salaboy.rolo.server.events.IncomingActionEvent;
import com.salaboy.rolo.events.ExternalNotificationEvent;
import com.salaboy.rolo.the.robot.comm.HornetQSessionWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.logging.Level;
import javax.enterprise.event.Event;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;

import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientProducer;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.ClientSessionFactory;
import org.hornetq.api.core.client.HornetQClient;
import org.hornetq.api.core.client.ServerLocator;
import org.hornetq.core.config.Configuration;
import org.hornetq.core.config.impl.ConfigurationImpl;
import org.hornetq.core.remoting.impl.netty.NettyAcceptorFactory;
import org.hornetq.core.remoting.impl.netty.NettyConnectorFactory;
import org.hornetq.core.remoting.impl.netty.TransportConstants;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.server.HornetQServers;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * java -jar rolo-the-robot-main-1.0-SNAPSHOT.jar -t 400 -ip 192.168.0.x -port 5445
 */
@Singleton
public class RoloCommandServer implements Runnable {

    public static final String SERVER_TASK_COMMANDS_QUEUE = "commandsQueue";
    private static final Logger logger = LoggerFactory.getLogger(RoloCommandServer.class);
    private ServerLocator serverLocator;
    private HornetQServer server;
    
    @Inject
    @CompleteRobot
    private Robot rolo;
    
    @Inject
    private Event<IncomingActionEvent> incomingActions;

    private Configuration configuration;
    private boolean standalone = false;
    private String host;
    private int port;
    volatile boolean embeddedServerRunning;
    private boolean running;
    private ClientSession session;
    private ClientConsumer consumer;
    static boolean readSensors = true;
    static long defaultLatency = 100;
    private HornetQSessionWriter notifications;
    
    public static void main(String[] args) throws Exception {
        final Weld weld = new Weld();

        WeldContainer container = weld.initialize();

        RoloCommandServer roloCommandServer = container.instance().select(RoloCommandServer.class).get();

        // create Options object
        Options options = new Options();

        // add t option
        options.addOption("t", true, "sensors latency");
        options.addOption("ip", true, "host");
        options.addOption("port", true, "port");
        CommandLineParser parser = new PosixParser();
        CommandLine cmd = parser.parse(options, args);

        String sensorLatency = cmd.getOptionValue("t");
        if (sensorLatency == null) {
            System.out.println(" The Default Latency will be used: " + defaultLatency);
        } else {
            System.out.println(" The Latency will be set to: " + sensorLatency);
            defaultLatency = new Long(sensorLatency);
        }

        String ip = cmd.getOptionValue("ip");
        if (ip == null) {
            System.out.println(" The Default IP will be used: 127.0.0.1");
            roloCommandServer.setHost("127.0.0.1");

        } else {
            System.out.println(" The IP will be set to: " + ip);
            roloCommandServer.setHost(ip);
        }

        String port = cmd.getOptionValue("port");
        if (port == null) {
            System.out.println(" The Default Port will be used: 5445");
            roloCommandServer.setPort(5445);

        } else {
            System.out.println(" The Port will be set to: " + port);
            roloCommandServer.setPort(Integer.parseInt(port));
        }

        System.out.println("Starting Server Rolo ...");

        Thread thread = new Thread(roloCommandServer);
        thread.start();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                System.out.println("Shutdown Hook is running !");
                readSensors = false;
                weld.shutdown();
            }
        });

    }

    public RoloCommandServer() {
    }

    public RoloCommandServer(String host, int port, Configuration configuration, boolean standalone) {

        this.port = port;
        this.configuration = configuration;
        this.standalone = standalone;
        this.host = host;
    }

    public Robot getRolo() {
        return rolo;
    }
    
    

    @Override
    public void run() {
        try {
            start();
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger(RoloCommandServer.class.getName()).log(Level.SEVERE, null, ex);
        }
        ClientProducer producer = null;
        try {
            producer = session.createProducer("rolo-ui");
        } catch (HornetQException ex) {
            java.util.logging.Logger.getLogger(RoloCommandServer.class.getName()).log(Level.SEVERE, null, ex);
        }
        notifications = new HornetQSessionWriter(session, producer);
        
        while (running && !consumer.isClosed()) {

            try {
                ClientMessage message = consumer.receive();
                if (message != null) {

                    Object object = readMessage(message);
                    String[] values = object.toString().split("~");
                    incomingActions.fire(new IncomingActionEvent(values));

                   // notifications.write(object);
                }
            } catch (HornetQException e) {
                switch (e.getCode()) {
                    case HornetQException.OBJECT_CLOSED:
                        logger.warn("Rolo Server: HornetQ object closed error encountered: " + getClass() + " using port " + port, e);
                        break;
                    default:
                        logger.error(" +++ " + e.getMessage());
                        break;
                }
            } catch (Exception e) {
                logger.error("Server Exception with class " + getClass() + " using port " + port + " E: " + e.getMessage(), e);

            }
        }

    }
    public void onExternalNotification(@Observes ExternalNotificationEvent event){
       
        if(notifications != null){
            try {
                notifications.write(event.getPayload());
            } catch (IOException ex) {
                java.util.logging.Logger.getLogger(RoloCommandServer.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    private Object readMessage(ClientMessage msgReceived) throws IOException {
        int bodySize = msgReceived.getBodySize();
        byte[] message = new byte[bodySize];
        msgReceived.getBodyBuffer().readBytes(message);
        ByteArrayInputStream bais = new ByteArrayInputStream(message);
        try {
            ObjectInputStream ois = new ObjectInputStream(bais);
            return ois.readObject();
        } catch (IOException e) {
            throw new IOException("Error reading message", e);
        } catch (ClassNotFoundException e) {
            throw new IOException("Error creating message", e);
        }
    }

    public void start() throws Exception {

        Map<String, Object> connectionParams = new HashMap<String, Object>();
        connectionParams.put(TransportConstants.PORT_PROP_NAME, port);
        connectionParams.put(TransportConstants.HOST_PROP_NAME, host);

        if (!standalone) {
            if (configuration == null) {
                configuration = new ConfigurationImpl();
                configuration.setPersistenceEnabled(false);
                configuration.setSecurityEnabled(false);
                configuration.setClustered(false);
            }

            TransportConfiguration transpConf = new TransportConfiguration(NettyAcceptorFactory.class.getName(), connectionParams);

            HashSet<TransportConfiguration> setTransp = new HashSet<TransportConfiguration>();
            setTransp.add(transpConf);

            configuration.setAcceptorConfigurations(setTransp);

            server = HornetQServers.newHornetQServer(configuration);
            server.start();
            embeddedServerRunning = true;
        }

        TransportConfiguration transportConfiguration = new TransportConfiguration(NettyConnectorFactory.class.getCanonicalName(), connectionParams);
        serverLocator = HornetQClient.createServerLocatorWithoutHA(transportConfiguration);
        ClientSessionFactory factory = serverLocator.createSessionFactory(transportConfiguration);
        session = factory.createSession();
        try {
            session.createQueue(SERVER_TASK_COMMANDS_QUEUE, SERVER_TASK_COMMANDS_QUEUE, true);
        } catch (HornetQException e) {
            if (e.getCode() != HornetQException.QUEUE_EXISTS) {
                logger.info(e.getMessage());
                throw new RuntimeException("Server Exception with class " + getClass() + " using port " + port, e);
            }
        }
        consumer = session.createConsumer(SERVER_TASK_COMMANDS_QUEUE);
        session.start();
        
        System.out.println("\n\n ####################################################### ");
        System.out.println(" ####                 ROLO IS ALIVE !!!              ### ");
        System.out.println(" ####################################################### ");
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-mm-yy hh:mm:ss");
        System.out.println(" ### Date: " + dateFormat.format(new Date()));
        running = true;
    }

    public void stop() throws Exception {
        if (running) {
            running = false;
            closeAll();
        }
        if (embeddedServerRunning) {
            embeddedServerRunning = false;
            closeAll();
            server.stop();
            serverLocator.close();
        }
    }

    private void closeAll() throws HornetQException {
        if (!session.isClosed()) {
            session.close();
        }
        if (!consumer.isClosed()) {
            consumer.close();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public void setConfiguration(Configuration configuration) {
        this.configuration = configuration;
    }

    public boolean isStandalone() {
        return standalone;
    }

    public void setStandalone(boolean standalone) {
        this.standalone = standalone;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
}
