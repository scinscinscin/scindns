package me.scinorandex;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.*;
import java.util.Optional;

public class Main {
    static Config config = null;

    public static void main(String[] args) throws Exception {
        boolean runRoot = args.length > 0 && args[0].equals("--root");

        File file = new File(System.getProperty("user.dir") + "/config.json");
        System.out.println("loading configuration file from: " + file.getAbsolutePath());
        if (!file.exists()) throw new Error("config.json file is missing");

        Gson gson = new Gson();
        config = gson.fromJson(new FileReader(file), Config.class);

        try (DatagramSocket serverSocket = new DatagramSocket(runRoot ? 53 : 19999)) {
            System.out.println("UDP Server started on port " + (runRoot ? 53 : 19999));

            byte[] receiveData = new byte[1024];

            while (true)  {
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                serverSocket.receive(receivePacket);

                Connection conn = new Connection(receivePacket, serverSocket);
                new Thread(conn).start();
            }
        } catch (Exception e) {
            System.err.println("Error creating socket: " + e.getMessage());
        }
    }

}

class Connection implements Runnable {
    DatagramPacket receivePacket;
    DatagramSocket serverSocket;
    byte[] bytes;

    public Connection(DatagramPacket receivePacket, DatagramSocket serverSocket){
        this.receivePacket = receivePacket;
        this.bytes = receivePacket.getData();
        this.serverSocket = serverSocket;
    }

    public void run(){
        try{
            // determine if we should handle this or pass it off to the next client
            String value = determineIfRoutable(bytes, receivePacket.getAddress().getHostAddress());
            if(value == null) throw new Exception("not routable, go to upstream");

            // build response and send back to user.
            sendResponse(bytes, value, serverSocket, receivePacket.getAddress(), receivePacket.getPort());
        }catch(Exception ex){
            try{
                handoffToUpstream(bytes, serverSocket, receivePacket.getAddress(), receivePacket.getPort());
            }catch(IOException io){}
        }
    }

    public static void sendResponse(byte[] initialBytes, String response, DatagramSocket serverSocket, InetAddress address, int port) throws Exception {
        try{
            byte[] receiveData = new byte[1024];

            receiveData[0] = initialBytes[0]; receiveData[1] = initialBytes[1]; // copy transaction id
            receiveData[2] = (byte) 0x81; receiveData[3] = (byte) 0x80; // standard response
            receiveData[4] = initialBytes[4]; receiveData[5] = initialBytes[5]; // copy qdcount
            receiveData[6] = (byte) 0x00; receiveData[7] = (byte) 0x01; // only sending back 1 record
            receiveData[8] = (byte) 0x00; receiveData[9] = (byte) 0x00; // NSCOUNT = NONE
            receiveData[10] = (byte) 0x00; receiveData[11] = (byte) 0x00; // ARCOUNT = NONE

            int i = 12;
            for(; initialBytes[i] != 0x0; i++){
                receiveData[i] = initialBytes[i];
            } i += 1; // skip over the 0

            for(int l = 0; l < 4; l++)
                receiveData[i + l] = initialBytes[i + l];
            i += 4;

            receiveData[i++] = (byte) 0xC0; receiveData[i++] = (byte) 0x0C;
            receiveData[i++] = (byte) 0x00; receiveData[i++] = (byte) 0x01; // this should be type
            receiveData[i++] = (byte) 0x00; receiveData[i++] = (byte) 0x01; // class
            receiveData[i++] = (byte) 0x00; receiveData[i++] = (byte) 0x00; receiveData[i++] = (byte) 0x01; receiveData[i++] = (byte) 0x2C;
            receiveData[i++] = (byte) 0x00; receiveData[i++] = (byte) 0x04;

            byte[] ip = InetAddress.getByName(response).getAddress();
            receiveData[i++] = (byte) ip[0]; receiveData[i++] = (byte) ip[1]; receiveData[i++] = (byte) ip[2]; receiveData[i++] = (byte) ip[3];

            DatagramPacket responsePacket = new DatagramPacket(receiveData, i, address, port);
            serverSocket.send(responsePacket);
        }catch(IOException ioException){
            throw ioException;
        }
    }

    public static String determineIfRoutable(byte[] bytes, String remoteAddress){
        int currentByteIndex = 12;
        String hostname = "";

        while(bytes[currentByteIndex] != 0){
            int partLength = bytes[currentByteIndex++];
            for(int i = 0; i < partLength; i++)  hostname += String.format("%c", bytes[currentByteIndex + i]);
            currentByteIndex += partLength;
            if(bytes[currentByteIndex] != 0) hostname += ".";
        }

        currentByteIndex++; // consume trailing 0x0 marking end of hostname
        int type = (bytes[currentByteIndex] << 4) | bytes[currentByteIndex + 1];

        String finalHostname = hostname;
        Optional<DnsRecord> record = Main.config.getRecords().stream().filter(r -> {
            if(!r.getName().equals(finalHostname)) return false;
            if(type == 1 && r.getType().equals("A") == false) return false;
            return true;
        }).findFirst();
        if(record.isPresent() == false) return null;

        Optional<Resolution> reso = record.get().getResolutions().stream().filter(r -> {
            try{
                return SubnetChecker.isInSubnet(remoteAddress, r.getNetwork(), r.getMask());
            }catch(Exception ex){ return false; }
        }).findFirst();
        if(reso.isPresent() == false) return null;

        System.out.println("Found applicable response, " + reso.get().getValue());
        return reso.get().getValue();
    }


    public static void handoffToUpstream(byte[] initialBytes, DatagramSocket serverSocket, InetAddress address, int port)
            throws IOException {

        try(DatagramSocket upstreamServerSocket = new DatagramSocket()){
            // send request to upstream DNS server
            InetAddress serverIP = InetAddress.getByName(Main.config.getUpstream());
            DatagramPacket sendPacket = new DatagramPacket(initialBytes, initialBytes.length, serverIP, Main.config.getPort());
            upstreamServerSocket.send(sendPacket);

            byte[] receiveData = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            upstreamServerSocket.receive(receivePacket);

            // forward response from upstream to client which asked for query
            serverSocket.send(new DatagramPacket(receivePacket.getData(), receivePacket.getLength(), address, port));
        }catch(IOException ioException){
            throw ioException;
        }
    }


    public static String createByteString(byte[] bytes, int len){
        return createByteString(bytes, 0, len);
    }

    public static String createByteString(byte[] bytes, int offset, int len){
        String ret = "";
        for(int i = offset; i < len; i++)  ret = ret + String.format("0x%x ", bytes[i]);
        return ret;
    }
}

class SubnetChecker {
    public static boolean isInSubnet(String ipAddress, String networkAddress, String subnetMask) throws UnknownHostException {
        byte[] ip = InetAddress.getByName(ipAddress).getAddress();
        byte[] network = InetAddress.getByName(networkAddress).getAddress();
        byte[] mask = InetAddress.getByName(subnetMask).getAddress();

        for (int i = 0; i < ip.length; i++) {
            if ((ip[i] & mask[i]) != (network[i] & mask[i])) {
                return false;
            }
        }

        return true;
    }
}