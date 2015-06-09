package com.ibm.streamsx.inet.wsserver;
/*
# Licensed Materials - Property of IBM
# Copyright IBM Corp. 2013, 2014
*/
import java.util.Iterator;
import java.util.Queue;

import org.apache.log4j.Logger;

import com.ibm.json.java.JSONArray;
import com.ibm.json.java.JSONObject;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.encoding.EncodingFactory;
import com.ibm.streams.operator.encoding.JSONEncoding;
import com.ibm.streams.operator.log4j.TraceLevel;
import com.ibm.streams.operator.metrics.Metric;
import com.ibm.streams.operator.metrics.Metric.Kind;
import com.ibm.streams.operator.model.CustomMetric;
import com.ibm.streams.operator.model.InputPortSet;
import com.ibm.streams.operator.model.InputPortSet.WindowMode;
import com.ibm.streams.operator.model.InputPorts;
import com.ibm.streams.operator.model.Libraries;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.model.PrimitiveOperator;
import com.ibm.streams.operator.AbstractOperator;
import com.ibm.streams.operator.StreamingInput;
import com.ibm.streams.operator.Tuple;

/**
 * A Sink class operator that is connected to multiple websocket clients, 
 * messages received on the input port are sent out as json messages. 
 * 
 * Clients can drop and connect at anytime. Message are only sent to those
 * that are connected at the time that the tuple arrives on the input port.  
 * 
 */

@PrimitiveOperator( description=WebSocketSend.primDesc)
@InputPorts({@InputPortSet(description=WebSocketSend.parmPortDesc, cardinality=1, optional=false, windowingMode=WindowMode.NonWindowed)})
@Libraries("opt/wssupport/java_websocket.jar")
public class WebSocketSend extends AbstractOperator {

	final static String primDesc =
			"Operator transmits tuples recieved on the input port via WebSocket protocol to connected clients." +			
			"Upon startup, starts WebSocket Server. As tuple arrives on the input port they're converted into" +
			"JSON formatted messages " +
			"and transmitted to all currently connected clients. Clients can connect and disconnect at anytime.";

	final static String parmPortDesc =
			"Port that clients connect to and tuples formatted as JSON message are transmitted over.";

	static final String CLASS_NAME="com.ibm.streamsx.inet.wsserver";
	private static Logger trace = Logger.getLogger(CLASS_NAME);	
	
	private WSServer wsServer;
    private int portNum;
    private Metric nMessagesSent;
    private Metric nClientsConnected;
    

    @CustomMetric(description="Number of messages sent using WebSocket", kind=Kind.COUNTER)
    public void setnMessagesSent(Metric nPostRequests) {
        this.nMessagesSent = nPostRequests;
    }
    public Metric getnMessagesSent() {
        return nMessagesSent;
    }    

    @CustomMetric(description="Number of clients currently using WebSocket", kind=Kind.GAUGE)
    public void setnClientsConnected(Metric nClientsConnected) {
        this.nClientsConnected = nClientsConnected;
    }
    public Metric getnClientsConnected() {
        return nClientsConnected;
    }    
    
	private boolean active = false;

    
	// Mandatory port     
    @Parameter(name="port", optional=false, description=parmPortDesc)
	public void setPort(int portNum) {
    	this.portNum = portNum;
    }
	public int getPort() {
		return this.portNum;
	}

    /**
     * Initialize this operator. Called once before any tuples are processed.
     * @param context OperatorContext for this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
	@Override
	public synchronized void initialize(OperatorContext context)
			throws Exception {
		super.initialize(context);

        // Setup connection 
        // TODO what to do if you have error here
        wsServer = new WSServer(portNum);
        active = true;
        wsServer.setWebSocketSink(this);
	}

    /**
     * Notification that initialization is complete and all input and output ports 
     * are connected and ready to receive and submit tuples.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void allPortsReady() throws Exception {
        OperatorContext context = getOperatorContext();
        trace.log(TraceLevel.INFO,"allPortsReady(): Operator " + context.getName() + " all ports are ready in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() + " starting WebServer.");
        // Start the server, it will wait for new client connections - send tuples to all connected clients. 
        wsServer.start();
    }

    /**
     * Process incoming tuples by submitting them directly to the
     * wsServer
     */
    @Override
    public synchronized void process(StreamingInput<Tuple> stream, Tuple tuple)
            throws Exception {

    	JSONEncoding jsonEncoding = EncodingFactory.getJSONEncoding();
    	
        trace.log(TraceLevel.INFO,"process()");
        JSONArray tuples = new JSONArray();


        JSONObject jsonTuple = new JSONObject();
        jsonTuple.put("tuple", jsonEncoding.encodeTuple(tuple));
        tuples.add(jsonTuple);

        JSONObject message = new JSONObject();
        message.put("tuples", tuples);
        int sentCount = wsServer.sendToAll(message);
        trace.log(TraceLevel.INFO,"process() : sent:" + sentCount);
        getnClientsConnected().setValue(sentCount);
        getnMessagesSent().setValue(wsServer.getTotalSentCount());
    }

    /**
     * Shutdown this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void shutdown() throws Exception {
        super.shutdown();    	
        OperatorContext context = getOperatorContext();
        trace.log(TraceLevel.INFO,"shutdown():Operator " + context.getName() + " shutting down in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() + " stopping WebServer.");
        active = false;
        wsServer.stop();        

    }

}

