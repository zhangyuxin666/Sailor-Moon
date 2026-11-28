package com.activityassistant.service;

import com.activityassistant.persistence.mapper.ActivityReactionMapper;
import com.activityassistant.persistence.mapper.BehaviorMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActivityReactionServiceTest {
    private final ActivityReactionMapper reactions = mock(ActivityReactionMapper.class);
    private final BehaviorMapper behavior = mock(BehaviorMapper.class);
    private final ActivityReactionService service = new ActivityReactionService(reactions, behavior);

    @Test
    void repeatedFavoriteDoesNotRepeatEventOrInterestScore() {
        when(behavior.visibleCategory("member", "activity")).thenReturn("technology");
        when(reactions.add("member", "activity", "favorite", "technology")).thenReturn(1, 0);

        service.change("member", "activity", "favorite", true);
        service.change("member", "activity", "favorite", true);

        verify(behavior).record(eq("member"), anyString(), eq("activity"), eq("favorite"), eq("technology"), eq(0));
        verify(behavior).aggregate("member", "technology", 0, 0, 0, 5);
    }

    @Test
    void removalSubtractsScoreFromOriginalCategoryEvenAfterTitleChanges() {
        when(behavior.visibleCategory("member", "activity")).thenReturn("sports");
        when(reactions.remove("member", "activity", "like")).thenReturn("technology", (String) null);

        service.change("member", "activity", "like", false);
        service.change("member", "activity", "like", false);

        verify(behavior).record(eq("member"), anyString(), eq("activity"), eq("like_removed"), eq("technology"), eq(0));
        verify(behavior).aggregate("member", "technology", 0, 0, 0, -3);
        verify(behavior, never()).aggregate(eq("member"), eq("sports"), anyInt(), anyInt(), anyInt(), anyInt());
    }
}
