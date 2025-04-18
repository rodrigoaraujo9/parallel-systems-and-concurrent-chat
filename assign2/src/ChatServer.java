import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class ChatServer implements Runnable {
    @Override
    public void run() {
        try{
            ServerSocket serverSocket = new ServerSocket(9999);
            Socket client = serverSocket.accept();
        }
        catch(Exception e){
            //TODO: handle exeption
        }



    }

    class ConnectionHandler implements Runnable {
        private Socket client;
        private BufferedReader input;
        private PrintWriter output;


        public ConnectionHandler(Socket client) {
            this.client = client;
        }


        @Override
        public void run() {
            try {
                output = new PrintWriter(client.getOutputStream(), true);
                input = new BufferedReader(new InputStreamReader(client.getInputStream()));
            } catch (IOException e) {
                //TODO: handle exeption
            }
        }

    }
}
