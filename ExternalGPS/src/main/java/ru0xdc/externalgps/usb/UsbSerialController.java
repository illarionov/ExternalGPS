/*
 * Copyright (C) 2013 Alexey Illarionov
 *
 * This file is part of UsbGPS4Droid.
 *
 * UsbGPS4Droid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * UsbGPS4Droid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with UsbGPS4Droid. If not, see <http://www.gnu.org/licenses/>.
 */

package ru0xdc.externalgps.usb;

import android.app.PendingIntent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.util.Log;

import proguard.annotation.KeepName;
import ru0xdc.externalgps.BuildConfig;

import proguard.annotation.KeepClassMembers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

public abstract class UsbSerialController {

	// Debugging
	private static final String TAG = UsbSerialController.class.getSimpleName();
    private static final boolean D = BuildConfig.DEBUG & true;

    protected UsbManager mUsbManager;
	protected UsbDevice mUsbDevice;

	public UsbSerialController(UsbManager usbManager,
			UsbDevice usbDevice) throws UsbControllerException {
		this.mUsbDevice = usbDevice;
		this.mUsbManager = usbManager;
	}

	public abstract void attach() throws UsbControllerException;
	public abstract void detach();

	/**
	 * Set serial line configuration
	 */
	public abstract void setSerialLineConfiguration(final SerialLineConfiguration config);

	/**
	 * @return serial line configuration
	 */
	public abstract SerialLineConfiguration getSerialLineConfiguration();


	public abstract UsbSerialInputStream getInputStream();
	public abstract UsbSerialOutputStream getOutputStream();

	public boolean hasPermission() {
		return this.mUsbManager.hasPermission(this.mUsbDevice);
	}

	public void requestPermission(PendingIntent pi) {
		this.mUsbManager.requestPermission(mUsbDevice, pi);
	}

	public static class UsbControllerException extends Exception {
		private static final long serialVersionUID = 1L;
		public UsbControllerException(String msg) { super(msg); }
	}

	public UsbDevice getDevice() {
	    return mUsbDevice;
	}

	protected static class UsbSerialInterruptListener extends Thread {

		private boolean cancelRequested = false;
		private UsbDeviceConnection mUsbConnection;
		private ByteBuffer buffer;
		private UsbRequest request;

		public UsbSerialInterruptListener(UsbDeviceConnection connection, UsbEndpoint endpoint) {
			this.mUsbConnection = connection;
			this.setName("PL2303InterruptListener");
			buffer =  ByteBuffer.allocate(endpoint.getMaxPacketSize());
			request = new UsbRequest();
			request.initialize(connection, endpoint);
		}

		@Override
		public void run() {
			mainloop: while(!cancelRequested()) {
				request.queue(buffer, buffer.capacity());
				if (mUsbConnection.requestWait() == request) {
					if (D) Log.v(TAG, "Interrupt received: " + buffer.toString() +
							Arrays.toString(buffer.array()));
					synchronized(this) {
						try {
							this.wait(100);
						} catch (InterruptedException e) {
							break mainloop;
						}
						if (cancelRequested) break mainloop;
					}
				}else {
					Log.e(TAG, "requestWait failed, exiting");
					break mainloop;
				}
			}
			Log.d(TAG, "Pl2303InterruptListener thread stopped");
		}

		public synchronized void cancel() {
			cancelRequested = true;
			this.notify();
		}

		private synchronized boolean cancelRequested() {
			return this.cancelRequested;
		}

	}

	@KeepClassMembers
	public interface UsbSerialStream {
	    public int getFileDescriptor();

        public int getMaxPacketSize();

        public int getEndpointAddress();
	}

    @KeepName
	public class UsbSerialInputStream extends InputStream implements UsbSerialStream {

		private static final int DEFAULT_READ_TIMEOUT_MS = 30000;
		private int mTimeout = DEFAULT_READ_TIMEOUT_MS;

		private UsbDeviceConnection mUsbConnection;
		private UsbEndpoint mUsbEndpoint;
		private byte rcvPkt[] = null;

		protected UsbSerialInputStream() {
		}

		public UsbSerialInputStream(UsbDeviceConnection connection,
				UsbEndpoint bulkInEndpoint,
				int writeTmoutMs
				) {
			mUsbConnection = connection;
			mUsbEndpoint = bulkInEndpoint;
			mTimeout = writeTmoutMs;
			rcvPkt = new byte[mUsbEndpoint.getMaxPacketSize()];
		}

		public UsbSerialInputStream(UsbDeviceConnection connection,
				UsbEndpoint bulkOutEndpoint) {
			this(connection, bulkOutEndpoint, DEFAULT_READ_TIMEOUT_MS);
		}

		@Override
        public int getFileDescriptor() {
		    return mUsbConnection.getFileDescriptor();
		}

		@Override
        public int getMaxPacketSize() {
		    return mUsbEndpoint.getMaxPacketSize();
		}

		@Override
		public int getEndpointAddress() {
		    return mUsbEndpoint.getAddress();
		}

		@Override
		public int read() throws IOException {
			synchronized(this) {
				int rcvd = read(rcvPkt, 0, 1);
				if (rcvd == 0) throw new IOException("timeout");
				return rcvPkt[0] & 0xff;
			}
		}

		@Override
		public int read(byte[] buffer, int offset, int count) throws IOException {
			int rcvd;

			synchronized(this) {
				if (offset == 0) {
					rcvd = mUsbConnection.bulkTransfer(mUsbEndpoint, buffer,
							count, mTimeout);
					if (rcvd < 0) throw new IOException("bulkTransfer() error");
					//if (D) Log.d(TAG, "Received " + rcvd + " bytes aligned");
					return rcvd;
				}else {
					rcvd = mUsbConnection.bulkTransfer(mUsbEndpoint,
							rcvPkt,
							Math.min(count, rcvPkt.length),
							mTimeout);
					if (rcvd < 0) throw new IOException("bulkTransfer() error");
					else if (rcvd > 0) {
						System.arraycopy(rcvPkt, 0, buffer, offset, rcvd);
					}
					if (D) Log.d(TAG, "Received " + rcvd + " bytes");
					return rcvd;
				}
			}
		}
	}

    @KeepName
	public class UsbSerialOutputStream extends OutputStream implements UsbSerialStream {

		private static final int DEFAULT_WRITE_TIMEOUT_MS = 2000;
		private int mTimeout = DEFAULT_WRITE_TIMEOUT_MS;

		private UsbDeviceConnection mUsbConnection;
		private UsbEndpoint mUsbEndpoint;
		private byte sndPkt[] = null;

		public UsbSerialOutputStream(UsbDeviceConnection connection,
				UsbEndpoint bulkOutEndpoint,
				int writeTmoutMs
				) {
			mUsbConnection = connection;
			mUsbEndpoint = bulkOutEndpoint;
			mTimeout = writeTmoutMs;
			sndPkt = new byte[mUsbEndpoint.getMaxPacketSize()];
		}

		public UsbSerialOutputStream(UsbDeviceConnection connection,
				UsbEndpoint bulkOutEndpoint) {
			this(connection, bulkOutEndpoint, DEFAULT_WRITE_TIMEOUT_MS);
		}

        @Override
        public int getFileDescriptor() {
            return mUsbConnection.getFileDescriptor();
        }

        @Override
        public int getMaxPacketSize() {
            return mUsbEndpoint.getMaxPacketSize();
        }

        @Override
        public int getEndpointAddress() {
            return mUsbEndpoint.getAddress();
        }

		@Override
		public void write(int arg0) throws IOException {
			write(new byte[] { (byte) arg0 } );
		}

		@Override
		public void write(byte[] buffer, int offset, int count) throws IOException {
			synchronized(this) {
				while(count>0) {
					/* XXX: timeout */
					int length = count > sndPkt.length ? sndPkt.length : count;
					System.arraycopy(buffer, offset, sndPkt, 0, length);
					int snd = mUsbConnection.bulkTransfer(mUsbEndpoint, sndPkt, length, mTimeout);
					if (snd<0) throw new IOException("bulkTransfer() failed");
					count -= snd;
					offset += snd;
				}
			}
		}
	}

}

