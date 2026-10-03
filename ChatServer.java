import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ChatServer {
    private static int port = 12345;
    private static final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private static ServerSocket serverSocket;
    private static volatile boolean running = true;

    public static void main(String[] args) {
        Scanner consoleScanner = new Scanner(System.in);

        System.out.print("Enter server port (Press Enter for 12345): ");
        String portInput = consoleScanner.nextLine().trim();
        if (!portInput.isEmpty()) {
            try {
                port = Integer.parseInt(portInput);
            } catch (NumberFormatException e) {
                System.out.println("Invalid port. Using default 12345.");
            }
        }

        try {
            serverSocket = new ServerSocket(port);
            System.out.println("=== Chat Server Running on Port " + port + " ===");
            System.out.println("Type '/exit' or 'exit' at any time to stop the server gracefully.\n");

            // Server Shutdown Console Command Loop
            new Thread(() -> {
                while (running) {
                    if (consoleScanner.hasNextLine()) {
                        String cmd = consoleScanner.nextLine().trim();
                        if ("/exit".equalsIgnoreCase(cmd) || "exit".equalsIgnoreCase(cmd)) {
                            shutdownServer();
                            break;
                        }
                    }
                }
            }).start();

            // Client Connection Accept Loop
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    ClientHandler client = new ClientHandler(socket);
                    clients.add(client);
                    new Thread(client).start();
                } catch (SocketException e) {
                    // Triggers when serverSocket is closed during shutdown
                    break;
                }
            }

        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    public static void broadcast(String message, ClientHandler sender) {
        for (ClientHandler client : clients) {
            if (client != sender) {
                client.sendMessage(message);
            }
        }
    }

    public static void removeClient(ClientHandler client) {
        clients.remove(client);
    }

    private static void shutdownServer() {
        System.out.println("\n[Server] Shutting down server gracefully...");
        running = false;
        
        // Notify all clients before closing
        for (ClientHandler client : clients) {
            client.sendMessage("SERVER_SHUTDOWN");
            client.closeConnection();
        }
        clients.clear();

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}

        System.out.println("[Server] Server stopped successfully.");
        System.exit(0);
    }

    static class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private String username = "Anonymous";

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                out.println("ENTER_NAME");
                String inputName = in.readLine();
                if (inputName != null && !inputName.trim().isEmpty()) {
                    username = inputName.trim();
                } else {
                    username = "User-" + socket.getPort();
                }

                System.out.println("[+] " + username + " connected from " + socket.getInetAddress().getHostAddress());
                broadcast(">>> " + username + " joined the chat.", this);

                String input;
                while ((input = in.readLine()) != null) {
                    if ("/exit".equalsIgnoreCase(input.trim()) || "exit".equalsIgnoreCase(input.trim())) {
                        break;
                    }

                    if (input.startsWith("FILE_OFFER|")) {
                        broadcast(input, this);
                    } else if (!input.trim().isEmpty()) {
                        broadcast("[" + username + "]: " + input, this);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                closeConnection();
                removeClient(this);
                System.out.println("[-] " + username + " disconnected.");
                broadcast("<<< " + username + " left the chat.", this);
            }
        }

        public void sendMessage(String msg) {
            if (out != null) {
                out.println(msg);
            }
        }

        public void closeConnection() {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ignored) {}
        }
    }
}