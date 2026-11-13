package edu.northwestu.project1p2.repositories;

import edu.northwestu.project1p2.entities.OAuthClient;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OAuthClientRepository extends CrudRepository<OAuthClient, Long> {

    Optional<OAuthClient> findByRegistrationId(String registrationId);
}
