package database.entity;

public record UserEntity(String userId, String username, String passwordHash, String salt, String email) {}
