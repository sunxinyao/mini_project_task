package com.service.task.mapper;

import com.service.task.entity.WorkerDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface WorkerMapper {

    @Insert("""
            INSERT INTO `worker` (`worker_id`, `created_at`, `last_seen_at`)
            VALUES (#{workerId}, #{now}, #{now})
            """)
    int insertWorker(@Param("workerId") String workerId, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM `worker` WHERE `worker_id` = #{workerId}")
    WorkerDO selectByWorkerId(@Param("workerId") String workerId);

    @Update("UPDATE `worker` SET `last_seen_at` = #{now} WHERE `worker_id` = #{workerId}")
    int touchLastSeen(@Param("workerId") String workerId, @Param("now") LocalDateTime now);

    @Update("UPDATE `worker` SET `last_claim_at` = #{now}, `claim_count` = `claim_count` + 1 WHERE `worker_id` = #{workerId}")
    int recordClaim(@Param("workerId") String workerId, @Param("now") LocalDateTime now);
}
