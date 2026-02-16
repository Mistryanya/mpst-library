package org.example.mpstlibrary.repo;

import org.example.mpstlibrary.data.CurrentState;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CurrentStateRepository extends CrudRepository<CurrentState, String> {
}
