package com.musio.cli.setup;

import com.musio.cli.config.MusioCliConfig;
import com.musio.cli.config.MusioCliConfigStore;
import com.musio.cli.process.BrowserLauncher;
import com.musio.cli.process.LocalProcessManager;
import com.musio.cli.ui.CliTimeline;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

public class StartupWorkflow {
    public int run() {
        MusioCliConfig config = new MusioCliConfigStore().load();
        List<MusicSourceOption> selectedSources = new SourceSelectionPrompt().select();
        if (selectedSources.isEmpty()) {
            System.out.println("Musio 启动已取消。");
            return 1;
        }

        String selectedSourceNames = selectedSources.stream()
                .map(MusicSourceOption::displayName)
                .collect(Collectors.joining(", "));
        CliTimeline.step("已选择音乐源");
        CliTimeline.success(selectedSourceNames);

        LocalProcessManager processManager = new LocalProcessManager(config);
        CliTimeline.step("项目目录");
        CliTimeline.detail(processManager.root().toString());

        boolean servicesReady = processManager.startRequiredServices();
        if (!servicesReady) {
            CliTimeline.warning("部分服务尚未 ready，请查看日志：" + processManager.runDirectory());
        }

        CliTimeline.step("访问入口");
        CliTimeline.detail("Backend  " + config.backendBaseUrl());
        CliTimeline.detail("Web      " + processManager.webBaseUrl());

        URI loginUri = URI.create(processManager.webBaseUrl() + "/?sources=" + sourceIds(selectedSources));
        CliTimeline.step("登录页面");
        CliTimeline.detail(loginUri.toString());
        if (new BrowserLauncher().open(loginUri)) {
            CliTimeline.success("已尝试在浏览器中打开登录页面");
        } else {
            CliTimeline.warning("未能自动打开浏览器，请手动复制上面的登录页面地址");
        }
        CliTimeline.end("Musio 启动流程完成");
        return 0;
    }

    private String sourceIds(List<MusicSourceOption> selectedSources) {
        return selectedSources.stream()
                .map(MusicSourceOption::id)
                .collect(Collectors.joining(","));
    }
}
