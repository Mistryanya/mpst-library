package org.example.mpstlibrary.repo;

import org.example.mpstlibrary.data.CurrentWorkflow;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CurrentWorkflowRepository extends CrudRepository<CurrentWorkflow, String> {
}
