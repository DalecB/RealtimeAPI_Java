package com.jake.realtimeapi.projects.application.model;

import com.jake.realtimeapi.apikeys.application.model.IssuedApiKeyResult;
import com.jake.realtimeapi.projects.domain.model.Project;

public record CreatedProjectWithApiKeyResult(
        Project project,
        IssuedApiKeyResult defaultApiKey
) {
}
