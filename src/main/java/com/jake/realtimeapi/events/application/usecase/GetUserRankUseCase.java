package com.jake.realtimeapi.events.application.usecase;

import com.jake.realtimeapi.events.application.query.GetUserRankQuery;
import com.jake.realtimeapi.events.domain.model.UserRankResult;

public interface GetUserRankUseCase {

    UserRankResult getUserRank(GetUserRankQuery query);
}
