package server.mapper;

import database.entity.UserEntity;
import server.model.User;

public final class UserMapper {

    private UserMapper() {}

    public static User mapToUser(UserEntity userEntity) {
        if (userEntity == null) {
            return null;
        }
        return new User(
                userEntity.userId(),
                userEntity.username(),
                userEntity.passwordHash(),
                userEntity.salt(),
                userEntity.email());
    }
}
