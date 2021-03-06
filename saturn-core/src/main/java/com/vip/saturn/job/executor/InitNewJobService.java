package com.vip.saturn.job.executor;

import com.google.common.collect.Maps;
import com.vip.saturn.job.basic.JobScheduler;
import com.vip.saturn.job.basic.SaturnExecutorContext;
import com.vip.saturn.job.exception.JobException;
import com.vip.saturn.job.exception.JobInitException;
import com.vip.saturn.job.exception.SaturnJobException;
import com.vip.saturn.job.internal.config.ConfigurationNode;
import com.vip.saturn.job.internal.config.JobConfiguration;
import com.vip.saturn.job.internal.storage.JobNodePath;
import com.vip.saturn.job.reg.base.CoordinatorRegistryCenter;
import com.vip.saturn.job.threads.SaturnThreadFactory;
import com.vip.saturn.job.utils.AlarmUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.curator.utils.CloseableExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.vip.saturn.job.executor.SaturnExecutorService.WAIT_JOBCLASS_ADDED_COUNT;

/**
 * @author hebelala
 */
public class InitNewJobService {

	private static final Logger log = LoggerFactory.getLogger(InitNewJobService.class);
	private static final String ERR_MSG_JOB_IS_ON_DELETING = "the job is on deleting";

	private SaturnExecutorService saturnExecutorService;
	private String executorName;
	private CoordinatorRegistryCenter regCenter;

	private TreeCache treeCache;
	private ExecutorService executorService;

	private List<String> jobNames = new ArrayList<>();

	public InitNewJobService(SaturnExecutorService saturnExecutorService) {
		this.saturnExecutorService = saturnExecutorService;
		this.executorName = saturnExecutorService.getExecutorName();
		this.regCenter = saturnExecutorService.getCoordinatorRegistryCenter();
	}

	public void start() throws Exception {
		treeCache = TreeCache.newBuilder((CuratorFramework) regCenter.getRawClient(), JobNodePath.ROOT).setExecutor(
				new CloseableExecutorService(Executors
						.newSingleThreadExecutor(new SaturnThreadFactory(executorName + "-$Jobs-watcher", false)),
						true)).setMaxDepth(1).build();
		executorService = Executors
				.newSingleThreadExecutor(new SaturnThreadFactory(executorName + "-initNewJob-thread", false));
		treeCache.getListenable().addListener(new InitNewJobListener(), executorService);
		treeCache.start();
	}

	public void shutdown() {
		try {
			if (treeCache != null) {
				treeCache.close();
			}
		} catch (Throwable t) {
			log.error(t.getMessage(), t);
		}
		try {
			if (executorService != null && !executorService.isTerminated()) {
				executorService.shutdownNow();
				int count = 0;
				while (!executorService.awaitTermination(50, TimeUnit.MILLISECONDS)) {
					if (++count == 4) {
						log.info("InitNewJob executorService try to shutdown now");
						count = 0;
					}
					executorService.shutdownNow();
				}
			}
		} catch (Throwable t) {
			log.error(t.getMessage(), t);
		}
	}

	public boolean removeJobName(String jobName) {
		return jobNames.remove(jobName);
	}

	class InitNewJobListener implements TreeCacheListener {

		@Override
		public void childEvent(CuratorFramework client, TreeCacheEvent event) throws Exception {
			if (event == null) {
				return;
			}

			ChildData data = event.getData();
			if (data == null) {
				return;
			}

			String path = data.getPath();
			if (path == null || path.equals(JobNodePath.ROOT)) {
				return;
			}

			TreeCacheEvent.Type type = event.getType();
			if (type == null || !type.equals(TreeCacheEvent.Type.NODE_ADDED)) {
				return;
			}

			String jobName = StringUtils.substringAfterLast(path, "/");
			String jobClassPath = JobNodePath.getNodeFullPath(jobName, ConfigurationNode.JOB_CLASS);
			// wait 5 seconds at most until jobClass created.
			for (int i = 0; i < WAIT_JOBCLASS_ADDED_COUNT; i++) {
				if (!regCenter.isExisted(jobClassPath)) {
					Thread.sleep(200L);
					continue;
				}

				log.info("new job: {} 's jobClass created event received", jobName);

				if (!jobNames.contains(jobName)) {
					if (initJobScheduler(jobName)) {
						jobNames.add(jobName);
						log.info("the job {} initialize successfully", jobName);
					}
				} else {
					log.warn("the job {} is unnecessary to initialize, because it's already existing", jobName);
				}
				break;
			}
		}

		public Map<String, Object> constructAlarmInfo(String namespace, String jobName, String executorName,
				String alarmMessage) {
			Map<String, Object> alarmInfo = new HashMap<>();

			alarmInfo.put("jobName", jobName);
			alarmInfo.put("executorName", executorName);
			alarmInfo.put("name", "Saturn Event");
			alarmInfo.put("title", String.format("JOB_INIT_FAIL:%s", jobName));
			alarmInfo.put("level", "CRITICAL");
			alarmInfo.put("message", alarmMessage);

			Map<String, String> customFields = Maps.newHashMap();
			customFields.put("sourceType", "saturn");
			customFields.put("domain", namespace);
			alarmInfo.put("additionalInfo", customFields);

			return alarmInfo;
		}

		private boolean initJobScheduler(String jobName) throws SaturnJobException {
			try {
				log.info("[{}] msg=add new job {} - {}", jobName, executorName, jobName);
				JobConfiguration jobConfig = new JobConfiguration(regCenter, jobName);
				if (jobConfig.getSaturnJobClass() == null) {
					throw new JobInitException("the saturnJobClass is null, jobType is {}", jobConfig.getJobType());
				}
				if (jobConfig.isDeleting()) {
					String serverNodePath = JobNodePath.getServerNodePath(jobName, executorName);
					regCenter.remove(serverNodePath);
					log.warn(ERR_MSG_JOB_IS_ON_DELETING);
					return false;
				}
				JobScheduler scheduler = new JobScheduler(regCenter, jobConfig);
				scheduler.setSaturnExecutorService(saturnExecutorService);
				scheduler.init();
				return true;
			} catch (JobInitException e) {
				// no need to log exception stack as it should be logged in the original happen place
				if (!SaturnExecutorContext.containsJobInitExceptionMessage(jobName, e.getMessage())) {
					String namespace = regCenter.getNamespace();
					AlarmUtils.raiseAlarm(constructAlarmInfo(namespace, jobName, executorName, e.getMessage()),
							namespace);
					SaturnExecutorContext.putJobInitExceptionMessage(jobName, e.getMessage());
				} else {
					log.info(
							"job {} init fail but will not raise alarm as such kind of alarm already been raise before",
							jobName);
				}
			} catch (Throwable e) {
				log.warn(String.format("job {} initialize fail, but will not stop the init process", jobName), e);
			}

			return false;
		}

	}

}
