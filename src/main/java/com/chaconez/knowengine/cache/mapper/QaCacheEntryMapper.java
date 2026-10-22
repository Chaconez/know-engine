package com.chaconez.knowengine.cache.mapper;

import com.chaconez.knowengine.cache.entity.QaCacheEntry;
import com.chaconez.knowengine.cache.entity.QaMiningEvidence;
import com.chaconez.knowengine.cache.service.QaCacheEntryService.Usage;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface QaCacheEntryMapper extends BaseMapper<QaCacheEntry> {

    /**
     * 判断当前问题之前是否已有会话消息。
     */
    @Select(
            """
            SELECT EXISTS(SELECT 1 FROM chat_message WHERE conversation_id=#{conversationId} AND deleted=0
            AND id < (SELECT id FROM chat_message WHERE message_id=#{messageId}))
            """)
    boolean hasHistory(
            @Param("conversationId") String conversationId, @Param("messageId") String messageId);

    /**
     * 按来源指纹幂等保存待审核候选。
     */
    @Insert(
            """
            INSERT INTO qa_cache_entry(id,fingerprint,question_key,question,answer,sources,frequency,quality,reason,status)
            VALUES (#{entry.id},#{entry.fingerprint},#{entry.questionKey},#{entry.question},#{entry.answer},#{entry.sources},#{entry.frequency},#{entry.quality},#{entry.reason},'PENDING')
            ON DUPLICATE KEY UPDATE fingerprint=qa_cache_entry.fingerprint
            """)
    int insertIfAbsent(@Param("entry") QaCacheEntry entry);

    /**
     * 启用已审核条目并记录首次生效时间。
     */
    @Update(
            """
            UPDATE qa_cache_entry SET status='ACTIVE',index_error=NULL,activated_at=COALESCE(activated_at,CURRENT_TIMESTAMP) WHERE id=#{id} AND status='APPROVED' AND expires_at>CURRENT_TIMESTAMP
            """)
    int activate(@Param("id") String id);

    /**
     * 重新激活已下线条目，恢复为待发布状态。
     */
    @Update(
            """
            UPDATE qa_cache_entry SET status='APPROVED',reviewer=#{reviewer},review_note=#{note},expires_at=#{expiry},disabled_at=NULL,disable_reason=NULL,disable_detail=NULL,vector_deleted=0 WHERE id=#{id} AND status='DISABLED'
            """)
    int reactivate(@Param("id") String id, @Param("reviewer") String reviewer,
                   @Param("note") String note, @Param("expiry") LocalDateTime expiry);

    /**
     * 查询并锁定缓存条目。
     */
    @Select(
            """
            SELECT * FROM qa_cache_entry WHERE id=#{id} FOR UPDATE
            """)
    QaCacheEntry lock(@Param("id") String id);

    /**
     * 累加缓存命中次数。
     */
    @Update(
            """
            UPDATE qa_cache_entry SET hits=hits+1 WHERE id=#{id}
            """)
    int hit(@Param("id") String id);

    /**
     * 记录回答的缓存命中标记和关联条目。
     */
    @Update(
            """
            UPDATE chat_message SET cache_hit=1, metadata=JSON_SET(COALESCE(metadata,JSON_OBJECT()),'$.qaCacheEntryId',#{entryId}) WHERE message_id=#{assistantId}
            """)
    int markCacheAnswer(@Param("assistantId") String assistantId, @Param("entryId") String entryId);

    /**
     * 统计指定时间窗口内的 RAG 回答数（粗口径分母）和条目命中数。
     */
    @Select(
            """
            SELECT COUNT(*) AS queries, COALESCE(SUM(CASE WHEN m.cache_hit=1 AND m.content IS NOT NULL AND m.content<>''
            AND JSON_UNQUOTE(JSON_EXTRACT(m.metadata,'$.qaCacheEntryId'))=#{id} THEN 1 ELSE 0 END),0) AS hits
            FROM chat_message m WHERE m.type='ASSISTANT' AND m.deleted=0
              AND m.rag_references IS NOT NULL AND JSON_LENGTH(m.rag_references)>0
              AND m.created_at>=#{since}
            """)
    @ConstructorArgs({
        @Arg(column = "queries", javaType = long.class),
        @Arg(column = "hits", javaType = long.class)
    })
    Usage usage(@Param("id") String id, @Param("since") LocalDateTime since);

    String MINING_HISTORY =
            """
            SELECT a.id answer_id,q.message_id question_message_id,a.conversation_id,
                   q.content question,a.content answer,a.rag_references refs
            FROM chat_message a JOIN chat_message q ON q.id=(
                SELECT MAX(p.id) FROM chat_message p
                WHERE p.conversation_id=a.conversation_id AND p.id&lt;a.id AND p.deleted=0)
            WHERE a.type='ASSISTANT' AND a.deleted=0 AND a.created_at>=#{since} AND a.created_at&lt;#{until}
              AND q.type='USER' AND q.deleted=0 AND CHAR_LENGTH(q.content) BETWEEN 5 AND 500
              AND NOT EXISTS(SELECT 1 FROM chat_message prev
                  WHERE prev.conversation_id=q.conversation_id AND prev.id&lt;q.id AND prev.deleted=0)
              AND CHAR_LENGTH(a.content) BETWEEN 30 AND 8000
              AND a.rag_references IS NOT NULL AND JSON_LENGTH(a.rag_references)>0
              AND (a.helpful IS NULL OR a.helpful=1)
            """;

    /**
     * 分页查询时间窗口内的合格首轮问答。
     */
    @Select(
            "<script>"
                    + MINING_HISTORY
                    + " AND a.id>#{afterId} ORDER BY a.id LIMIT #{limit}</script>")
    List<QaMiningEvidence> miningHistory(
            @Param("since") LocalDateTime since,
            @Param("until") LocalDateTime until,
            @Param("afterId") long afterId,
            @Param("limit") int limit);

    /**
     * 复查来源问答是否仍符合提取条件。
     */
    @Select(
            "<script>"
                    + MINING_HISTORY
                    + " AND a.id IN <foreach collection='ids' item='id' open='(' separator=','"
                    + " close=')'>#{id}</foreach></script>")
    List<QaMiningEvidence> recheckMining(
            @Param("ids") List<Long> ids,
            @Param("since") LocalDateTime since,
            @Param("until") LocalDateTime until);

}
