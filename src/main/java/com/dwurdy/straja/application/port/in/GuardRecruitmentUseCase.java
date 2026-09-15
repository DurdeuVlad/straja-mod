package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import java.util.Optional;

/**
 * Inbound port for the recruitment chain (§5/§6): the receptionist records
 * the application, and the trainer ("Instructorul") is also the recruiter
 * ("Recrutorul"): the same NPC runs the admission quiz, training modules,
 * service-block progress, promotion requests and the physical theory manual.
 * The prompt carries the
 * server-selected persisted question ID; answers are bound to that ID so a
 * stale or foreign form can never advance the quiz.
 */
public interface GuardRecruitmentUseCase {
    record QuizPrompt(String questionId, String title, String question, int maxLength) {}

    /**
     * Read-only trainer projection: service-block points, the configured
     * requirement for the next rank and whether the player may self-promote.
     * {@code nextRank}/{@code requiredBlocks} are null at the top rank or
     * when the next rank has no configured threshold.
     */
    record TrainingView(int rank, long serviceBlocks, Integer nextRank, Integer requiredBlocks,
                        boolean canPromote, boolean hasManual, int pendingModules) {}

    Optional<QuizPrompt> currentQuizPrompt(PlayerGateway player);

    boolean answerQuiz(PlayerGateway player, String expectedQuestionId, String answer);

    /**
     * Receptionist "Depune cererea" action (§5): records the application and
     * directs the applicant to the Instructor. Idempotent — re-applying while
     * APPLIED is a no-op tell; invited/guarded/fired states stay explicit.
     */
    void applyForStraja(PlayerGateway player);

    /** Instructor/Recrutor admission action — checks stay in the service. */
    void recruit(PlayerGateway player);

    /** Trainer projection used to mint the state-aware promotion button. */
    TrainingView trainingView(PlayerGateway player);

    /** Points/progress readout: service blocks vs. the next-rank threshold. */
    void showProgress(PlayerGateway player);

    /**
     * Self-service rank-up at the trainer: succeeds only when the configured
     * service-block threshold is met; ranks without a configured threshold
     * stay a commissioner decision.
     */
    void requestPromotion(PlayerGateway player);

    /** Hands the physical theory manual once per guard; inventory-checked. */
    void giveManual(PlayerGateway player);

    /**
     * Self-declared native faction: players keep their origin allegiance on
     * record while acting as Străjeri during duty. Blank/"none" clears it.
     */
    boolean declareNativeFaction(PlayerGateway player, String faction);

    /** Field bound for the native-faction declaration form. */
    int nativeFactionMaxLength();
}
