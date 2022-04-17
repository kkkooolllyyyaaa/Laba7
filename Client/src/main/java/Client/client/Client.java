package Client.client;

import Client.authorizer.ClientAuthorizer;
import Client.commands.*;
import Client.connection.ClientConnectionManager;
import Client.connection.request.RequestSender;
import Client.connection.response.ResponseReader;
import exceptions.CommandIsNotExistException;
import general.*;
import validation.StudyGroupBuilder;
import validation.StudyGroupBuilderImpl;
import validation.StudyGroupValidatorImpl;

import java.io.IOException;
import java.io.StreamCorruptedException;
import java.net.ConnectException;
import java.net.SocketException;
import java.nio.BufferOverflowException;
import java.nio.channels.SocketChannel;
import java.util.NoSuchElementException;

import static general.IO.errPrint;
import static general.IO.getReader;


public class Client implements ClientApp {
    private final ClientCommandReaderImpl commandReader;
    private final ClientConnectionManager connectionManager;
    private final RequestSender requestSender;
    private final ResponseReader responseReader;
    private final ClientAuthorizer authorizer;
    private final int port;
    private final StudyGroupBuilder studyGroupBuilder;
    private static User currentUser;
    private boolean isRunning;

    public Client(ClientCommandReaderImpl commandReader,
                  ClientConnectionManager connectionManager,
                  RequestSender requestSender,
                  ResponseReader responseReader,
                  ClientAuthorizer authorizer,
                  int port) {
        this.commandReader = commandReader;
        this.connectionManager = connectionManager;
        this.requestSender = requestSender;
        this.responseReader = responseReader;
        this.authorizer = authorizer;
        this.port = port;
        studyGroupBuilder = new StudyGroupBuilderImpl(getReader(), false, new StudyGroupValidatorImpl());
        isRunning = true;
        addCommands();
    }

    /**
     * Метод начинает работу программы клиента
     * Обеспечивает ввод пользователя и контролирует общение с сервером
     */
    @Override
    public void start(int port) {
        while (isRunning) {
            String inputString = "";
            try {
                inputString = IO.readLine();
                if (inputString == null)
                    throw new NoSuchElementException();
                commandReader.executeCommand(inputString, null);
            } catch (CommandIsNotExistException e) {
                try {
                    Response response = communicateWithServer(inputString);
                    if (response == null) {
                        IO.println("Response wasn't received, wait until the server is available");
                        return;
                    }
                    IO.println(response.getMessage());
                } catch (IOException | ClassNotFoundException ioException) {
                    ioException.printStackTrace();
                }
            } catch (NoSuchElementException e) {
                IO.errPrint("You can't input this\nThe work of Client will be stopped");
                return;
            } catch (IOException e) {
                IO.errPrint(e.getMessage());
                return;
            }
        }
    }

    /**
     * Останавливает последующие итерации работы программы
     */
    @Override
    public void exit() {
        isRunning = false;
    }

    /**
     * Отправляет запрос серверу
     *
     * @param inputString ввод пользователя
     * @return Response - ответ сервера
     */
    public Response communicateWithServer(String inputString) throws IOException, ClassNotFoundException {
        String[] commandAndArgument = inputString.trim().split("\\s", 2);
        SocketChannel socketChannel;
        try {
            socketChannel = connectionManager.openConnection(port);
        } catch (ConnectException e) {
            IO.println("Server is unavailable");
            return null;
        }
        Request request = requestSender.createBasicRequest(commandAndArgument[0]);
        if (commandAndArgument.length > 1)
            request.setArg(commandAndArgument[1]);
        request.setUser(currentUser);
        requestSender.sendRequest(socketChannel, request);
        Response response = null;
        try {
            response = responseReader.readResponse(socketChannel);
        } catch (BufferOverflowException e) {
            errPrint("Server data is too big for buffer");
        } catch (StreamCorruptedException e) {
            errPrint("Server is unavailable now");
            exit();
        } catch (SocketException e) {
            errPrint("Connection is refused, the work will stop");
            exit();
        }
        connectionManager.closeConnection();
        if (response == null) {
            exit();
            IO.println("Connection refused");
            return null;
        } else if (response.getResponseType().equals(ResponseType.STUDY_GROUP_RESPONSE)) {
            request.setUser(currentUser);
            return reCommunicateWithServer(request);
        }
        return response;
    }

    /**
     * Отправляет запрос серверу, вместе с элементом коллекции
     *
     * @return Response - ответ сервера
     */
    private Response reCommunicateWithServer(Request request) throws IOException, ClassNotFoundException {
        String arg = request.getArg();
        SocketChannel socketChannel = connectionManager.openConnection(port);
        StudyGroup studyGroup = studyGroupBuilder.askStudyGroup();
        studyGroup.setUsername(currentUser.getUserName());
        request = requestSender.createExecuteRequest(request.getCommandName(), studyGroup);
        request.setArg(arg);
        request.setUser(getCurrentUser());
        requestSender.sendRequest(socketChannel, request);
        Response response;
        try {
            response = responseReader.readResponse(socketChannel);
        } catch (StreamCorruptedException e) {
            return null;
        } catch (BufferOverflowException e) {
            response = new Response();
            response.setMessage("Server data is too big for buffer");
        }
        connectionManager.closeConnection();
        return response;
    }

    /**
     * Добавляет все клиентские команды
     */
    private void addCommands() {
        commandReader.addCommand("client_help", new ClientHelpCommand(commandReader));
        commandReader.addCommand("exit", new ClientExitCommand(this));
        commandReader.addCommand("execute_script", new ExecuteScriptCommand(this, commandReader));
        commandReader.addCommand("auth", new ClientAuthCommand(authorizer));
        commandReader.addCommand("register", new ClientRegisterCommand(authorizer));
        commandReader.addCommand("current_user", new CurrentUserCommand());
    }

    public static User getCurrentUser() {
        return currentUser;
    }

    public static void setCurrentUser(User currentUser) {
        Client.currentUser = currentUser;
    }
}
