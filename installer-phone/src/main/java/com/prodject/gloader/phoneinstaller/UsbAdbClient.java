package com.prodject.gloader.phoneinstaller;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class UsbAdbClient implements Closeable {
    private static final int A_SYNC = command("SYNC");
    private static final int A_CNXN = command("CNXN");
    private static final int A_AUTH = command("AUTH");
    private static final int A_OPEN = command("OPEN");
    private static final int A_OKAY = command("OKAY");
    private static final int A_CLSE = command("CLSE");
    private static final int A_WRTE = command("WRTE");
    private static final int A_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 4096;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_PUBLIC_KEY = 3;
    private static final int USB_TIMEOUT_MS = 10_000;

    private final UsbDeviceConnection connection;
    private final UsbInterface usbInterface;
    private final UsbEndpoint inEndpoint;
    private final UsbEndpoint outEndpoint;
    private final AdbInstallRunner.Logger logger;
    private int nextLocalId = 1;

    private UsbAdbClient(
            UsbDeviceConnection connection,
            UsbInterface usbInterface,
            UsbEndpoint inEndpoint,
            UsbEndpoint outEndpoint,
            AdbInstallRunner.Logger logger) {
        this.connection = connection;
        this.usbInterface = usbInterface;
        this.inEndpoint = inEndpoint;
        this.outEndpoint = outEndpoint;
        this.logger = logger;
    }

    static UsbAdbClient open(
            Context context,
            UsbManager manager,
            UsbDevice device,
            AdbInstallRunner.Logger logger) throws Exception {
        UsbInterface adbInterface = null;
        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getInterfaceClass() == 0xff
                    && candidate.getInterfaceSubclass() == 0x42
                    && candidate.getInterfaceProtocol() == 0x01) {
                adbInterface = candidate;
                for (int e = 0; e < candidate.getEndpointCount(); e++) {
                    UsbEndpoint endpoint = candidate.getEndpoint(e);
                    if (endpoint.getType() == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (endpoint.getDirection() == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                            in = endpoint;
                        } else {
                            out = endpoint;
                        }
                    }
                }
                break;
            }
        }
        if (adbInterface == null || in == null || out == null) {
            throw new IllegalStateException("ADB bulk interface not found");
        }
        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            throw new IllegalStateException("Cannot open USB device");
        }
        if (!connection.claimInterface(adbInterface, true)) {
            connection.close();
            throw new IllegalStateException("Cannot claim ADB USB interface");
        }
        UsbAdbClient client = new UsbAdbClient(connection, adbInterface, in, out, logger);
        client.connect(AdbCrypto.loadOrCreate(context));
        return client;
    }

    private void connect(AdbCrypto crypto) throws Exception {
        send(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.US_ASCII));
        while (true) {
            Message message = readMessage();
            if (message.command == A_CNXN) {
                logger.log("ADB connected: " + new String(message.payload, StandardCharsets.US_ASCII).trim());
                return;
            }
            if (message.command == A_AUTH && message.arg0 == AUTH_TOKEN) {
                logger.log("ADB auth requested");
                send(A_AUTH, AUTH_SIGNATURE, 0, crypto.sign(message.payload));
                Message next = readMessage();
                if (next.command == A_CNXN) {
                    logger.log("ADB connected after signature auth");
                    return;
                }
                if (next.command == A_AUTH && next.arg0 == AUTH_TOKEN) {
                    logger.log("Sending ADB public key; confirm authorization on the head unit if prompted");
                    send(A_AUTH, AUTH_PUBLIC_KEY, 0, crypto.publicKeyPayload());
                    continue;
                }
                throw new IllegalStateException("Unexpected ADB auth response: " + name(next.command));
            }
            throw new IllegalStateException("Unexpected ADB handshake message: " + name(message.command));
        }
    }

    String shell(String command) throws Exception {
        Stream stream = openStream("shell:" + command);
        return readStreamToClose(stream);
    }

    String shellInteractive(String command) throws Exception {
        Stream stream = openStream("shell:");
        writeStream(stream, (command + "\nexit\n").getBytes(StandardCharsets.UTF_8));
        return readStreamToClose(stream);
    }

    private String readStreamToClose(Stream stream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            Message message = readMessage();
            if (message.command == A_WRTE && message.arg1 == stream.localId) {
                output.write(message.payload);
                send(A_OKAY, stream.localId, message.arg0, new byte[0]);
            } else if (message.command == A_CLSE && message.arg1 == stream.localId) {
                send(A_CLSE, stream.localId, message.arg0, new byte[0]);
                return output.toString("UTF-8");
            }
        }
    }

    void push(File source, String remotePath, int mode) throws Exception {
        Stream stream = openStream("sync:");
        SyncBuffer sync = new SyncBuffer();
        writeStream(stream, syncPacket("SEND", (remotePath + "," + mode).getBytes(StandardCharsets.UTF_8)));
        try (FileInputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[MAX_PAYLOAD - 8];
            int count;
            while ((count = input.read(buffer)) != -1) {
                writeStream(stream, syncPacket("DATA", Arrays.copyOf(buffer, count)));
            }
        }
        ByteBuffer done = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        done.putInt((int) (System.currentTimeMillis() / 1000L));
        writeStream(stream, syncPacket("DONE", done.array()));
        byte[] status = sync.readExactly(this, stream, 8);
        String id = new String(status, 0, 4, StandardCharsets.US_ASCII);
        int length = leInt(status, 4);
        if ("OKAY".equals(id)) {
            closeStream(stream);
            return;
        }
        byte[] detail = length > 0 ? sync.readExactly(this, stream, length) : new byte[0];
        closeStream(stream);
        throw new IllegalStateException("ADB push failed: " + id + " " + new String(detail, StandardCharsets.UTF_8));
    }

    private Stream openStream(String destination) throws Exception {
        int localId = nextLocalId++;
        send(A_OPEN, localId, 0, (destination + "\0").getBytes(StandardCharsets.UTF_8));
        while (true) {
            Message message = readMessage();
            if (message.command == A_OKAY && message.arg1 == localId) {
                return new Stream(localId, message.arg0);
            }
            if (message.command == A_CLSE && message.arg1 == localId) {
                throw new IllegalStateException("ADB stream closed while opening " + destination);
            }
        }
    }

    private void writeStream(Stream stream, byte[] payload) throws Exception {
        send(A_WRTE, stream.localId, stream.remoteId, payload);
        while (true) {
            Message message = readMessage();
            if (message.command == A_OKAY && message.arg1 == stream.localId) {
                return;
            }
            if (message.command == A_CLSE && message.arg1 == stream.localId) {
                throw new IllegalStateException("ADB stream closed during write");
            }
        }
    }

    private void closeStream(Stream stream) throws Exception {
        send(A_CLSE, stream.localId, stream.remoteId, new byte[0]);
    }

    private byte[] syncPacket(String id, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(id.getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    private void send(int command, int arg0, int arg1, byte[] payload) throws Exception {
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command);
        header.putInt(arg0);
        header.putInt(arg1);
        header.putInt(payload.length);
        header.putInt(checksum(payload));
        header.putInt(command ^ 0xffffffff);
        bulkWrite(header.array());
        if (payload.length > 0) {
            bulkWrite(payload);
        }
    }

    private Message readMessage() throws Exception {
        byte[] header = bulkReadExact(24);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = buffer.getInt();
        int arg0 = buffer.getInt();
        int arg1 = buffer.getInt();
        int length = buffer.getInt();
        int checksum = buffer.getInt();
        int magic = buffer.getInt();
        if ((command ^ 0xffffffff) != magic) {
            throw new IllegalStateException("Bad ADB magic for " + name(command));
        }
        byte[] payload = length > 0 ? bulkReadExact(length) : new byte[0];
        if (checksum(payload) != checksum) {
            throw new IllegalStateException("Bad ADB checksum for " + name(command));
        }
        return new Message(command, arg0, arg1, payload);
    }

    private void bulkWrite(byte[] data) throws Exception {
        int offset = 0;
        while (offset < data.length) {
            int count = connection.bulkTransfer(outEndpoint, data, offset, data.length - offset, USB_TIMEOUT_MS);
            if (count <= 0) {
                throw new IllegalStateException("USB bulk write failed");
            }
            offset += count;
        }
    }

    private byte[] bulkReadExact(int length) throws Exception {
        byte[] output = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = connection.bulkTransfer(inEndpoint, output, offset, length - offset, USB_TIMEOUT_MS);
            if (count <= 0) {
                throw new IllegalStateException("USB bulk read failed");
            }
            offset += count;
        }
        return output;
    }

    private static int checksum(byte[] payload) {
        int sum = 0;
        for (byte b : payload) {
            sum += b & 0xff;
        }
        return sum;
    }

    private static int command(String name) {
        byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
        return (bytes[0] & 0xff)
                | ((bytes[1] & 0xff) << 8)
                | ((bytes[2] & 0xff) << 16)
                | ((bytes[3] & 0xff) << 24);
    }

    private static int leInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static String name(int command) {
        byte[] bytes = new byte[] {
                (byte) command,
                (byte) (command >> 8),
                (byte) (command >> 16),
                (byte) (command >> 24)
        };
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    @Override
    public void close() {
        connection.releaseInterface(usbInterface);
        connection.close();
    }

    private static final class Message {
        final int command;
        final int arg0;
        final int arg1;
        final byte[] payload;

        Message(int command, int arg0, int arg1, byte[] payload) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.payload = payload;
        }
    }

    private static final class Stream {
        final int localId;
        final int remoteId;

        Stream(int localId, int remoteId) {
            this.localId = localId;
            this.remoteId = remoteId;
        }
    }

    private static final class SyncBuffer {
        private final ByteArrayOutputStream buffered = new ByteArrayOutputStream();

        byte[] readExactly(UsbAdbClient client, Stream stream, int length) throws Exception {
            while (buffered.size() < length) {
                Message message = client.readMessage();
                if (message.command == A_WRTE && message.arg1 == stream.localId) {
                    buffered.write(message.payload);
                    client.send(A_OKAY, stream.localId, message.arg0, new byte[0]);
                } else if (message.command == A_CLSE && message.arg1 == stream.localId) {
                    throw new IllegalStateException("ADB sync stream closed");
                }
            }
            byte[] all = buffered.toByteArray();
            byte[] result = Arrays.copyOfRange(all, 0, length);
            buffered.reset();
            if (all.length > length) {
                buffered.write(all, length, all.length - length);
            }
            return result;
        }
    }
}
