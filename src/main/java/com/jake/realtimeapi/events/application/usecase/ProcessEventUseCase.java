package com.jake.realtimeapi.events.application.usecase;

import com.jake.realtimeapi.events.application.command.ProcessEventCommand;
import com.jake.realtimeapi.events.domain.model.ProcessEventResult;

public interface ProcessEventUseCase {

    ProcessEventResult process(ProcessEventCommand command);
}
