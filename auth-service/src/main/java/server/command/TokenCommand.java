package server.command;

public record TokenCommand(String token) implements AuthCommand {}
