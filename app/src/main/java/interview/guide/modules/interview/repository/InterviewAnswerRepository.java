package interview.guide.modules.interview.repository;

import interview.guide.modules.interview.model.PracticeAnswerEntity;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 面试答案Repository
 */
@Repository
public interface InterviewAnswerRepository extends JpaRepository<PracticeAnswerEntity, Long> {
    
    /**
     * 根据会话查找所有答案
     */
    List<PracticeAnswerEntity> findBySessionOrderByQuestionIndex(PracticeSessionEntity session);
    
    /**
     * 根据会话ID查找所有答案
     */
    List<PracticeAnswerEntity> findBySessionIdOrderByQuestionIndex(Long sessionId);
    
    /**
     * 根据会话 sessionId 字符串查找所有答案
     */
    List<PracticeAnswerEntity> findBySession_SessionIdOrderByQuestionIndex(String sessionId);

    /**
     * 根据会话 sessionId 和问题索引查找单条答案（用于 upsert）
     */
    Optional<PracticeAnswerEntity> findBySession_SessionIdAndQuestionIndex(String sessionId, Integer questionIndex);
}
