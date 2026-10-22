package com.chaconez.knowengine.chat.mapper;

import com.chaconez.knowengine.cache.service.QaCacheEntryService.FeedbackCounts;
import com.chaconez.knowengine.chat.entity.ChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI对话消息表 Mapper
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
    /**
     * 按用户统计缓存条目的首次评价。
     */
    @Select("""
        SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN votes.helpful=0 THEN 1 ELSE 0 END),0) AS negative
        FROM (
            SELECT m.helpful, ROW_NUMBER() OVER (
                PARTITION BY c.user_id ORDER BY m.feedback_at,m.id) AS vote_order
            FROM chat_message m JOIN chat_conversation c ON c.conversation_id=m.conversation_id
            WHERE m.helpful IS NOT NULL AND m.feedback_at IS NOT NULL AND m.type='ASSISTANT'
              AND m.deleted=0 AND c.deleted=0
              AND JSON_UNQUOTE(JSON_EXTRACT(m.metadata,'$.qaCacheEntryId'))=#{id}
        ) votes WHERE votes.vote_order=1
        """)
    @ConstructorArgs({@Arg(column="total",javaType=long.class),@Arg(column="negative",javaType=long.class)})
    FeedbackCounts feedbackCounts(@Param("id") String id);
}
