package interview.guide.modules.quiz.model;

import jakarta.validation.constraints.NotNull;

public record WrongQuestionStatusRequest(
    @NotNull WrongQuestionStatus status
) {
}
