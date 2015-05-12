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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class NioServer implements Runnable {
	private InetAddress hostAddress;
	private int port;
	
	private ServerSocketChannel serverChannel;
	
	private Selector selector;
	
	private ByteBuffer readBuffer = ByteBuffer.allocate( 8192 );
	
	private EchoWorker worker;
	
	private List pendingChanges = new LinkedList();
	
	private Map pendingData = new HashMap();
	
	private Selector initSelector() throws IOException {
		Selector socketSelector = SelectorProvider.provider().openSelector();
		
		this.serverChannel = ServerSocketChannel.open();
		serverChannel.configureBlocking( false );
		
		InetSocketAddress isa = new InetSocketAddress( this.hostAddress, this.port );
		serverChannel.socket().bind( isa );
		
		serverChannel.register( socketSelector, SelectionKey.OP_ACCEPT );
		
		return socketSelector;
	}
	
	public NioServer( InetAddress hostAddress, int port, EchoWorker worker ) throws IOException {
		this.hostAddress = hostAddress;
		this.port = port;
		this.selector = this.initSelector();
		this.worker = worker;
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
		
		this.worker.processData( this, socketChannel, this.readBuffer.array(), numRead );
	}
	
	public void send( SocketChannel socket, byte[] data ) {
		synchronized( this.pendingChanges ) {
			this.pendingChanges.add( new ChangeRequest( socket, ChangeRequest.CHANGEOPS, SelectionKey.OP_WRITE ) );
			
			synchronized( this.pendingData ) {
				List queue = (List)this.pendingData.get( socket );
				if( queue == null ) {
					queue = new ArrayList();
					this.pendingData.put( socket,  queue );
				}
				queue.add( ByteBuffer.wrap( data ) );
			}
		}
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
					
					if( key.isAcceptable() ) {
						this.accept( key );
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

	public static void main( String[] args ) {
		try {
			EchoWorker worker = new EchoWorker();
			new Thread( worker ).start();
			new Thread( new NioServer( null, 9090, worker ) ).start();
		} catch( IOException e ) {
			e.printStackTrace();
		}
	}
}
