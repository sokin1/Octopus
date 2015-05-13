package io.octopus.selectEx;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class NioClient implements Runnable {
	private InetAddress hostAddress;
	private int port;
	
	private Selector selector;
	
	private ByteBuffer readBuffer = ByteBuffer.allocate( 8192 );
	
	private List pendingChanges = new LinkedList();
	
	private Map pendingData = new HashMap();
	
	private Map rspHandlers = Collections.synchronizedMap( new HashMap() );
	
	private Selector initSelector() throws IOException {
		Selector socketSelector = SelectorProvider.provider().openSelector();
		
		return socketSelector;
	}
	
	public NioClient( InetAddress hostAddress, int port ) throws IOException {
		this.hostAddress = hostAddress;
		this.port = port;
		this.selector = this.initSelector();
	}
	
	private void accept( SelectionKey key ) throws IOException {
		ServerSocketChannel serverSocketChannel = (ServerSocketChannel)key.channel();
		
		SocketChannel socketChannel = serverSocketChannel.accept();
		Socket socket = socketChannel.socket();
		socketChannel.configureBlocking( false );
		
		socketChannel.register( this.selector, SelectionKey.OP_READ );
	}
	
	private void read( SelectionKey key ) throws IOException {
		SocketChannel socketChannel = (SocketChannel)key.channel();
		
		this.readBuffer.clear();
		
		int numRead;
		try {
			numRead = socketChannel.read( this.readBuffer );
		} catch( IOException e ) {
			key.cancel();
			socketChannel.close();
			
			return;
		}
		
		if( numRead == -1 ) {
			key.channel().close();
			key.cancel();
			
			return;
		}
		
		this.handleResponse( socketChannel, this.readBuffer.array(), numRead );
	}
	
	private void handleResponse( SocketChannel socketChannel, byte[] data, int numRead ) throws IOException {
		byte[] rspData = new byte[numRead];
		System.arraycopy( data, 0, rspData, 0, numRead );
		
		RspHandler handler = (RspHandler)this.rspHandlers.get( socketChannel );
		
		if( handler.handleResponse( rspData ) ) {
			socketChannel.close();
			socketChannel.keyFor( this.selector ).cancel();
		}
	}
	
	public void send( byte[] data, RspHandler handler ) {
		SocketChannel socket = this.initiateConnection();
		
		this.rspHandlers.put( socket, handler );
		
		synchronized( this.pendingData ) {
			List queue = (List)this.pendingData.get( socket );
			if( queue == null ) {
				queue = new ArrayList();
				this.pendingData.put( socket, queue );
			}
			queue.add( ByteBuffer.wrap( data ) );
		}
		
		this.selector.wakeup();
	}
	
	private void finishConnection( SelectionKey key ) throws IOException {
		SocketChannel socketChannel = (SocketChannel)key.channel();
		
		try {
			socketChannel.finishConnect();
		} catch( IOException e ) {
			key.cancel();
			return;
		}
		
		key.interestOps( SelectionKey.OP_WRITE );
	}

	@Override
	public void run() {
		while( true ) {
			try {
				synchronized( this.pendingChanges ) {
					Iterator changes = this.pendingChanges.iterator();
					while( changes.hasNext() ) {
						ChangeRequest change = (ChangeRequest)changes.next();
						switch( change.type ) {
						case ChangeRequest.CHANGEOPS:
							SelectionKey key = change.socket.keyFor( this.selector );
							key.interestOps( change.ops );
							break;
						case ChangeRequest.REGISTER:
							change.socket.register( this.selector, change.ops );
							break;
						}
					}
					this.pendingChanges.clear();
				}

				this.selector.select();
				
				Iterator selectedKeys = this.selector.selectedKeys().iterator();
				while( selectedKeys.hasNext() ) {
					SelectionKey key = (SelectionKey)selectedKeys.next();
					selectedKeys.remove();
					
					if( !key.isValid() ) {
						continue;
					}
					
					if( key.isConnectable() ) {
						this.finishConnection( key );
					} else if( key.isReadable() ) {
						this.read( key );
					} else if( key.isWritable() ) {
						this.write( key );
					}
				}
			} catch( Exception e ) {
				e.printStackTrace();
			}
		}
	}
	
	private void write( SelectionKey key ) throws IOException {
		SocketChannel socketChannel = (SocketChannel)key.channel();
		
		synchronized( this.pendingData ) {
			List queue = (List)this.pendingData.get( socketChannel );
			
			while( !queue.isEmpty() ) {
				ByteBuffer buf = (ByteBuffer)queue.get( 0 );
				socketChannel.write( buf );
				
				if( buf.remaining() > 0 ) {
					break;
				}
				queue.remove( 0 );
			}
			
			if( queue.isEmpty() ) {
				key.interestOps( SelectionKey.OP_READ );
			}
		}
	}
	
	private SocketChannel initiateConnection() throws IOException {
		SocketChannel socketChannel = SocketChannel.open();
		socketChannel.configureBlocking( false );
		
		socketChannel.connect( new InetSocketAddress( this.hostAddress, this.port ) );
		
		synchronized( this.pendingChanges ) {
			this.pendingChanges.add( new ChangeRequest( socketChannel, ChangeRequest.REGISTER, SelectionKey.OP_CONNECT ) );
		}

		return socketChannel;
	}

	public static void main( String[] args ) {
		try {
			NioClient client = new NioClient( InetAddress.getByName( "localhost" ), 9090 );
			Thread t = new Thread( client );
			t.setDaemon( true );
			t.start();
			
			RspHandler handler = new RspHandler();
			client.send( "Hello World".getBytes(), handler );
			handler.waitForResponse();
		} catch( Exception e ) {
			e.printStackTrace();
		}
		
	}
}
