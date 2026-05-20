package com.jake.realtimeapi.events.application.usecase;

import com.jake.realtimeapi.events.application.query.GetTopRanksQuery;
import com.jake.realtimeapi.events.domain.model.TopRanksResult;

public interface GetTopRanksUseCase {

    TopRanksResult getTopRanks(GetTopRanksQuery query);
}
