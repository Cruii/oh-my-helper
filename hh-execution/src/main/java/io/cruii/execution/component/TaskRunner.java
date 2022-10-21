package io.cruii.execution.component;

import cn.hutool.core.io.FileUtil;
import io.cruii.component.BilibiliDelegate;
import io.cruii.component.TaskExecutor;
import io.cruii.context.BilibiliUserContext;
import io.cruii.execution.feign.BilibiliFeignService;
import io.cruii.execution.feign.PushFeignService;
import io.cruii.pojo.po.BilibiliUser;
import io.cruii.pojo.po.TaskConfig;
import io.cruii.pojo.vo.BilibiliUserVO;
import ma.glasnost.orika.MapperFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author cruii
 * Created on 2022/10/17
 */
@Component
public class TaskRunner {

    private final ThreadPoolTaskExecutor taskExecutor;

    private final BilibiliFeignService bilibiliFeignService;

    private final PushFeignService pushFeignService;

    private final MapperFactory mapperFactory;

    public TaskRunner(ThreadPoolTaskExecutor taskExecutor,
                      BilibiliFeignService bilibiliFeignService,
                      PushFeignService pushFeignService,
                      MapperFactory mapperFactory) {
        this.taskExecutor = taskExecutor;
        this.bilibiliFeignService = bilibiliFeignService;
        this.pushFeignService = pushFeignService;
        this.mapperFactory = mapperFactory;
    }

    public void run(TaskConfig taskConfig) {
        taskExecutor.execute(() -> {
            BilibiliDelegate delegate = new BilibiliDelegate(taskConfig);
            String traceId = MDC.get("traceId");
            BilibiliUser user = delegate.getUser();
            BilibiliUserContext.set(user);
            TaskExecutor executor = new TaskExecutor(delegate);
            try {
                BilibiliUser retUser = executor.execute();
                retUser.setLastRunTime(LocalDateTime.now());
                MDC.clear();
                BilibiliUserVO bilibiliUserVO = mapperFactory.getMapperFacade().map(retUser, BilibiliUserVO.class);
                bilibiliFeignService.updateUser(bilibiliUserVO);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                BilibiliUserContext.remove();
                // 日志收集
                String date = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(LocalDate.now());
                File logFile = new File("logs/execution/all-" + date + ".log");
                String content = null;
                if (logFile.exists()) {
                    List<String> logs = FileUtil.readLines(logFile, StandardCharsets.UTF_8);

                    content = logs
                            .stream()
                            .filter(line -> line.contains(traceId) && (line.contains("INFO") || line.contains("ERROR")))
                            .map(line -> line.split("\\|\\|")[1])
                            .collect(Collectors.joining("\n"));
                }
                // 推送
                push(taskConfig.getDedeuserid(), content);
            }
        });
    }

    private void push(String dedeuserid, String content) {
        pushFeignService.push(dedeuserid, content);
    }
}
