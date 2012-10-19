package com.badlogic.gdx.backends.ios;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cli.System.AsyncCallback;
import cli.System.IAsyncResult;
import cli.System.Net.Dns;
import cli.System.Net.IPAddress;
import cli.System.Net.Sockets.AddressFamily;
import cli.System.Net.Sockets.TcpClient;
import cli.System.Net.Sockets.TcpListener;
import cli.System.Threading.ManualResetEvent;
import cli.System.Threading.Timeout;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Net.Protocol;
import com.badlogic.gdx.net.ServerSocket;
import com.badlogic.gdx.net.ServerSocketHints;
import com.badlogic.gdx.net.Socket;
import com.badlogic.gdx.net.SocketHints;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * iOS server socket implementation using System.Net.Sockets.TcpListener (Microsoft).
 * 
 * @author noblemaster
 */
public class IOSServerSocket implements ServerSocket {

	private Protocol protocol;
	public int port;
	private ServerSocketHints hints;
	
	/** Our listeners we have created. Will prevent "address already in use" errors if used more than once. */
	static Map<Integer, TcpListener> listeners = new HashMap<Integer, TcpListener>();
	/** Our clients that we have accepted or null for none (1 per port). We'll constantly poll in background. */
	Map<Integer, TcpClient> clients = new HashMap<Integer, TcpClient>();
	/** Our accept callbacks instantiated (1 per port). */
	Map<Integer, AsyncCallback> clientCallbacks = new HashMap<Integer, AsyncCallback>();
	/** Our accept callback method (so we can do accept timeouts!). */
	final AsyncCallback.Method clientCallbackMethod = new AsyncCallback.Method() {		
		@Override
		public void Invoke (IAsyncResult ar) {
		    // Get the listener that handles the client request.
		    TcpListener listener = (TcpListener)ar.get_AsyncState();
		
		    // End the operation and display the received data on the console.
		    clients.put(port, listener.EndAcceptTcpClient(ar));
		
		    // Process the connection here. (Add the client to a  server table, read data, etc.)
		    Gdx.app.debug("IOSServerSocket", "Client connected");
		}
	};
	

	/** The sockets that are connected. Will need to keep them to dispose. */
	private List<IOSSocket> sockets = new ArrayList<IOSSocket>();
	
	
	public IOSServerSocket(Protocol protocol, int port, final ServerSocketHints hints) {
		if (protocol == Protocol.TCP) {
			this.protocol = protocol;
			this.port = port;
			this.hints = hints;
			
			// create the server socket
			try {
				// we only create a new listener if one does not exist yet
				if (listeners.get(port) == null) {
					// get local IP to connect with
					IPAddress ipAddress = null;
					String host = Dns.GetHostName(); 
					host = host + ".local"; // the ".local" might not be needed on the simulator!?
					IPAddress[] ips = Dns.GetHostAddresses(host);
					for (int i = 0; i < ips.length; i++) {
						IPAddress ip = ips[i];
						if (ip.get_AddressFamily().Value == AddressFamily.InterNetwork) {
							ipAddress = ip;
							break;
						}
					}
					Gdx.app.debug("IOSServerSocket", "Binding server to " + ipAddress.ToString() + ":" + port);
					
					// initialize server		
					TcpListener listener = new TcpListener(ipAddress, port);
					listeners.put(port, listener);
					if (hints != null) {
						// NOTE: most server socket hints are not available on iOS - no performance parameters!
						listener.get_Server().set_ReceiveBufferSize(hints.receiveBufferSize);
					}
					
					// and bind the server...
					if (hints != null) {
						listener.Start(hints.backlog);
					}
					else {
						listener.Start();
					}
					
					// our accept listener (runs in background and waits for the next connection)
					// NOTE: listener.Pending() wasn't working, so we use async callbacks instead
					AsyncCallback clientCallback = new AsyncCallback(clientCallbackMethod);
					clientCallbacks.put(port, clientCallback);
					listener.BeginAcceptTcpClient(clientCallback, listener);
				}
			}
			catch (Exception e) {
				throw new GdxRuntimeException("Cannot create a server socket at port " + port + ".", e);
			}
		}
		else {
			throw new GdxRuntimeException("Server socket protocol " + protocol + " is not supported under iOS backend.");
		}
	}

	@Override
	public Protocol getProtocol () {
		return this.protocol;
	}

	@Override
	public synchronized Socket accept (SocketHints hints) {
		// our listener
		TcpListener listener = listeners.get(port);
		
		// accept with timeout as needed (not as well supported via C# as via Java - needs special impl. in C#)
		int timeout;
		if (IOSServerSocket.this.hints != null) {
			timeout = IOSServerSocket.this.hints.acceptTimeout;
			if (timeout == 0) {
				timeout = Timeout.Infinite;
			}
		}
		else {
			timeout = Timeout.Infinite;
		}
		
	    // check if we found a client previously
		TcpClient client = clients.get(port);
		if (client == null) {
			// Start to listen for connections from a client.
			Gdx.app.debug("IOSServerSocket", "Waiting for client connect...");
			
			// Waits for a connection or until the timeout is reached
		   int loopPause = 10;
		   int loops;
		   if (timeout == Timeout.Infinite) {
		   	loops = Integer.MAX_VALUE;
		   }
		   else {
		   	loops = timeout / loopPause;
		   }
		   for (int i = 0; i < loops; i++) {
		   	// client found?
		   	if (clients.get(port) != null) {
		   		break;
		   	}
		   	
		   	// server disposed?
		   	if (sockets == null) {
		   		throw new GdxRuntimeException("Server disposed: cannot accept any new clients.");
		   	}
		   	
		   	// wait for next check
		   	try {
		   		Thread.sleep(loopPause);
		   	}
		   	catch (InterruptedException e) {
		   		throw new GdxRuntimeException("Error in Thread.sleep.", e);
		   	}
		   }
		   
		   // try to get the client
		   client = clients.get(port);
		}
		
	   // connection received?
	   if (client != null) {
	   	// remove our found client from the list and start the callback again
	   	clients.remove(port);
	   	listener.BeginAcceptTcpClient(clientCallbacks.get(port), listener);
	   	
	   	// socket connection function
			Gdx.app.debug("IOSServerSocket", "Socket connected.");
			IOSSocket socket = new IOSSocket(this, client, hints);
			sockets.add(socket);
			return socket;
		}
	   else {
	   	// timeout reached
	   	throw new GdxRuntimeException("No socket connections received (timeout).");
	   }
	}

	@Override
	public void dispose () {
		// stop all our sockets
		if (sockets != null) {
			for (int i = 0; i < sockets.size(); i++) {
				IOSSocket socket = sockets.get(i);
				try {
					socket.dispose();
				}
				catch (Exception e) {
					Gdx.app.debug("IOSServerSocket", "Error disposing socket.", e);
				}
			}
			sockets = null;
		}
	}
	
	void dispose(IOSSocket socket) {
		sockets.remove(socket);
	}
}
