"""Offline unit tests for tools.perf_audit parsers, correlators, and state restore logic."""

from __future__ import annotations

import unittest

from tools.perf_audit.analyzer import (
    analyze_hal_and_sched_logs,
    compute_clock_offset_s,
    compute_tis_delta,
    correlate_jank_window,
    parse_gfxinfo_framestats,
    parse_root_ftrace,
)
from tools.perf_audit.auditors import (
    compute_thread_deltas,
    detect_periodic_logcat_spinners,
    parse_logcat_monotonic,
    parse_perf_opcode_xmls,
)
from tools.perf_audit.state_guard import build_restore_script, verify_snapshot_diff


class PerfAuditUnitTests(unittest.TestCase):
    def test_parse_perf_opcode_xmls_with_override(self) -> None:
        common = """<?xml version="1.0" encoding="utf-8"?>
        <PerfResources>
            <Major OpcodeValue="0x2" />
                <Minor OpcodeValue="0x2" Node="/sys/devices/system/cpu/cpufreq/policy0/walt/adaptive_low_freq" />
                <Minor OpcodeValue="0x3" Node="/sys/devices/system/cpu/cpufreq/policy0/walt/adaptive_high_freq" />
            <Major OpcodeValue="0x4" />
                <Minor OpcodeValue="0x0" Node="SPECIAL_NODE - lock_min_cores" />
        </PerfResources>
        """
        target = """<?xml version="1.0" encoding="utf-8"?>
        <PerfResources>
            <Major OpcodeValue="0x2" />
                <Minor OpcodeValue="0x3" Node="/sys/devices/system/cpu/cpufreq/policy5/walt/adaptive_high_freq" />
        </PerfResources>
        """
        opcode_map = parse_perf_opcode_xmls(common, target)
        self.assertEqual(opcode_map["4,0"]["node"], "SPECIAL_NODE - lock_min_cores")
        self.assertEqual(
            opcode_map["2,2"]["node"],
            "/sys/devices/system/cpu/cpufreq/policy0/walt/adaptive_low_freq",
        )
        self.assertEqual(
            opcode_map["2,3"]["node"],
            "/sys/devices/system/cpu/cpufreq/policy5/walt/adaptive_high_freq",
        )

    def test_parse_root_ftrace_waltgov_caps_and_dynamic_ux(self) -> None:
        sample = """
        # tracer: nop
        sugov:5-123   [005] ....  1200.500000: waltgov_next_freq: cpu=5 util=812 max=1024 raw_freq=2880000 freq=2035200 policy_min_freq=480000 policy_max_freq=2035200 cached_raw_freq=2880000 need_update=1 thermal_isolated=0 rt_util=0 driv_cpu=5 reason=0x1
        sugov:5-123   [005] ....  1200.505000: waltgov_next_freq: cpu=5 util=300 max=1024 raw_freq=1363200 freq=1363200 policy_min_freq=480000 policy_max_freq=2035200 cached_raw_freq=1363200 need_update=1 thermal_isolated=0 rt_util=0 driv_cpu=5 reason=0x1
        sugov:5-123   [005] ....  1200.508000: waltgov_next_freq: cpu=5 util=950 max=1024 raw_freq=4128000 freq=3302400 policy_min_freq=480000 policy_max_freq=3302400 cached_raw_freq=4128000 need_update=1 thermal_isolated=0 rt_util=0 driv_cpu=5 reason=0x1
        system_server-3086 [003] d..2  1200.510000: cpu_frequency_limits: min=480000 max=2035200 cpu_id=5
        Binder:3086_2-3200 [002] ....  1200.520000: dynamic_ux_set: pid=18285 comm=com.zui.launcher dynamic_type=1 ux_state=5 dynamic_ux=1 ux_depth=1
        """
        parsed = parse_root_ftrace(sample, hwmax_by_cpu={5: 3302400})
        cpu5 = parsed["waltgov_summary"]["cpu5"]
        self.assertEqual(cpu5["samples"], 3)
        self.assertEqual(cpu5["capped_samples"], 1)
        self.assertEqual(cpu5["hwmax_saturated_samples"], 1)
        self.assertAlmostEqual(cpu5["capped_pct"], 33.33, places=2)
        self.assertEqual(len(parsed["waltgov_cap_events"]), 1)
        self.assertEqual(len(parsed["cpu_frequency_limits"]), 1)
        self.assertEqual(parsed["dynamic_ux_by_comm"]["com.zui.launcher"], 1)

    def test_logcat_periodic_spinner_and_hal_decoding(self) -> None:
        lines = [
            "  100.000 15212 15212 I OvHyperScheduleTool: find null name wmshell.main",
            "  105.005 15212 15212 I OvHyperScheduleTool: find null name wmshell.main",
            "  110.009 15212 15212 I OvHyperScheduleTool: find null name wmshell.main",
            "  115.012 15212 15212 I OvHyperScheduleTool: find null name wmshell.main",
            "  112.100  2067  2090 E ANDR-PERF-RESOURCEQS: ANDR-PERF-RESOURCEQS: AddAndApply() 167: Failed to apply optimization [4, 0]",
            "  112.101  2067  2090 E ANDR-PERF-UTIL: ANDR-PERF-UTIL: change_node_val() 319: Failed to update node /sys/devices/system/cpu/cpufreq/policy5/walt/adaptive_high_freq with value 1000000",
            "  112.102  2067  2090 E ANDR-PERF-RESOURCEQS: ANDR-PERF-RESOURCEQS: AddAndApply() 167: Failed to apply optimization [2, 3]",
        ]
        spin_res = detect_periodic_logcat_spinners(lines)
        spinners = spin_res["periodic_spinners"]
        self.assertEqual(len(spinners), 1)
        self.assertEqual(spinners[0]["tag"], "OvHyperScheduleTool")
        self.assertAlmostEqual(spinners[0]["period_mean_s"], 5.004, places=2)
        self.assertLess(spinners[0]["cv"], 0.05)

        entries = parse_logcat_monotonic(lines)
        opcode_map = {
            "4,0": {"node": "SPECIAL_NODE - lock_min_cores"},
            "2,3": {"node": "/sys/devices/system/cpu/cpufreq/policy0/walt/adaptive_high_freq"},
        }
        hal = analyze_hal_and_sched_logs(entries, opcode_map, boottime_offset_s=1000.0)
        self.assertEqual(hal["decoded_resourceqs_failures"]["[4,0] -> SPECIAL_NODE - lock_min_cores"], 1)
        self.assertEqual(
            hal["decoded_resourceqs_failures"]["[2,3] -> /sys/devices/system/cpu/cpufreq/policy0/walt/adaptive_high_freq"],
            1,
        )
        self.assertEqual(
            hal["util_node_failures"]["/sys/devices/system/cpu/cpufreq/policy5/walt/adaptive_high_freq = 1000000"],
            1,
        )

    def test_parse_gfxinfo_framestats_and_profiledata(self) -> None:
        raw = """
        Stats since: 15461492195195ns
        Total frames rendered: 100
        Janky frames: 12 (12.00%)
        Janky frames (legacy): 15 (15.00%)
        50th percentile: 6ms
        90th percentile: 9ms
        95th percentile: 11ms
        99th percentile: 25ms
        Number Missed Vsync: 2
        Number High input latency: 10
        Number Slow UI thread: 11
        Number Slow bitmap uploads: 0
        Number Slow issue draw commands: 1
        Number Frame deadline missed: 12
        50th gpu percentile: 2ms
        99th gpu percentile: 4ms
        Scratch:
          Buffer Object: 563.62 KB (28 entries)
          Surface: 153.17 MB (279 entries)
        Total GPU memory usage:
          167271872 bytes, 159.52 MB (10.42 MB is purgeable)
        ---PROFILEDATA---
        Flags,IntendedVsync,Vsync,OldestInputEvent,NewestInputEvent,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,DequeueBufferDuration,QueueBufferDuration,GpuCompleted
        0,1000000000,1000100000,990000000,995000000,1000200000,1000500000,1001000000,1006000000,1008000000,1008100000,1008500000,1009500000,1010500000,100000,200000,1012000000
        1,2000000000,2000100000,0,0,2000200000,2000500000,2001000000,2006000000,2008000000,2008100000,2008500000,2009500000,2010500000,100000,200000,2012000000
        ---PROFILEDATA---
        """
        res = parse_gfxinfo_framestats(raw)
        self.assertEqual(res["summary"]["total_frames"], 100)
        self.assertEqual(res["summary"]["slow_ui_thread"], 11)
        self.assertEqual(res["summary"]["skia_scratch_surface_mb"], 153.17)
        self.assertEqual(res["summary"]["skia_scratch_surface_entries"], 279)
        self.assertEqual(res["profiledata_valid_frames"], 1)
        self.assertAlmostEqual(res["stages_ms"]["measure_layout_ms"]["mean"], 5.0, places=2)
        self.assertAlmostEqual(res["stages_ms"]["total_cpu_ms"]["mean"], 10.5, places=2)
        self.assertAlmostEqual(res["stages_ms"]["gpu_ms"]["mean"], 2.5, places=2)

    def test_clock_alignment_and_jank_window_correlation(self) -> None:
        logcat_entries = parse_logcat_monotonic(
            [
                " 100.000 10 10 I ZToolPerfAudit: ZTOOL_PERF_SYNC_START",
                " 110.000 10 10 I ZToolPerfAudit: ZTOOL_PERF_SYNC_END",
            ]
        )
        perfetto_anchors = [
            {"ts": 1100_000_000_000, "tag": "ZToolPerfAudit", "msg": "ZTOOL_PERF_SYNC_START"},
            {"ts": 1110_000_000_000, "tag": "ZToolPerfAudit", "msg": "ZTOOL_PERF_SYNC_END"},
        ]
        offset, source = compute_clock_offset_s(perfetto_anchors, {}, logcat_entries)
        self.assertEqual(source, "perfetto_android_logs")
        self.assertAlmostEqual(offset, 1000.0, places=3)

        corr = correlate_jank_window(
            frame={"start_s": 1105.0, "end_s": 1105.030},
            hal_events=[
                {"boottime_s": 1104.95, "tag": "ANDR-PERF-RESOURCEQS", "decoded_node": "lock_min_cores"},
                {"boottime_s": 1101.00, "tag": "ANDR-PERF-RESOURCEQS", "decoded_node": "lock_min_cores"},
            ],
            waltgov_caps=[{"ts_s": 1104.99, "cpu": 5, "raw_freq": 2880000, "policy_max_freq": 2035200}],
            sidecar_ticks=[{"uptime_s": 1105.02, "threads": [{"comm": "RenderThread", "cpus_allowed": "0-5"}]}],
        )
        self.assertEqual(corr["concurrent_hal_errors"], {"ANDR-PERF-RESOURCEQS:lock_min_cores": 1})
        self.assertEqual(corr["concurrent_waltgov_cap_count"], 1)
        self.assertIsNotNone(corr["sidecar_snapshot"])

    def test_state_guard_restore_ordering_and_mismatch_verification(self) -> None:
        snapshot = {
            "mode": "boost-ab",
            "package": "com.qimian233.ztool",
            "policies": {
                "policy5": {
                    "cpuinfo_min_freq": 480000,
                    "nodes": {
                        "scaling_min_freq": {
                            "path": "/sys/devices/system/cpu/cpufreq/policy5/scaling_min_freq",
                            "mode": "0644",
                            "value": "480000",
                        },
                        "scaling_max_freq": {
                            "path": "/sys/devices/system/cpu/cpufreq/policy5/scaling_max_freq",
                            "mode": "0444",
                            "value": "2035200",
                        },
                        "walt/adaptive_high_freq": {
                            "path": "/sys/devices/system/cpu/cpufreq/policy5/walt/adaptive_high_freq",
                            "mode": "0444",
                            "value": "0",
                        },
                    },
                }
            },
            "cpuctl": {
                "/dev/cpuctl/top-app/cpu.uclamp.min": {"mode": "0644", "value": "0.00"}
            },
            "settings": {"system:artificial_switch_of_power_saving": "1"},
            "thread_affinities": {
                "28984": {"comm": "qimian233.ztool", "cpus_allowed_hex": "3f", "cpus_allowed_list": "0-5"}
            },
            "applied_mutations": {
                "touched_walt_nodes": ["walt/adaptive_high_freq"],
                "touched_min_freq_policies": ["policy5"],
                "touched_msm_perf": False,
                "paused_pids": ["13348"],
                "static_ux_tids": {"28984": "0"},
            },
        }
        script = build_restore_script(snapshot)
        # Verify kernel-safe ordering: cpuinfo_min before orig_max before orig_min, then chmod 0444 last.
        idx_min_reset = script.index('printf "%s" 480000 > /sys/devices/system/cpu/cpufreq/policy5/scaling_min_freq')
        idx_max_restore = script.index('printf "%s" 2035200 > /sys/devices/system/cpu/cpufreq/policy5/scaling_max_freq')
        idx_chmod_ro = script.index("chmod 0444 /sys/devices/system/cpu/cpufreq/policy5/scaling_max_freq")
        idx_resume = script.index("kill -CONT 13348")
        self.assertLess(idx_min_reset, idx_max_restore)
        self.assertLess(idx_max_restore, idx_chmod_ro)
        self.assertLess(idx_chmod_ro, idx_resume)

        # Verify mismatch detection catches value & permission drift.
        live_drifted = {
            "policies": {
                "policy5": {
                    "nodes": {
                        "scaling_min_freq": {"mode": "0644", "value": "480000"},
                        "scaling_max_freq": {"mode": "0644", "value": "3302400"},
                        "walt/adaptive_high_freq": {"mode": "0444", "value": "0"},
                    }
                }
            },
            "cpuctl": {"/dev/cpuctl/top-app/cpu.uclamp.min": {"mode": "0644", "value": "0"}},
            "settings": {"system:artificial_switch_of_power_saving": "1"},
            "thread_affinities": {"28984": {"comm": "qimian233.ztool", "cpus_allowed_hex": "0000003f"}},
        }
        vr = verify_snapshot_diff(snapshot, live_drifted, mode_name="boost-ab")
        self.assertFalse(vr.ok)
        fields = {(m.target, m.field) for m in vr.mismatches}
        self.assertIn(("policy5/scaling_max_freq", "value"), fields)
        self.assertIn(("policy5/scaling_max_freq", "mode"), fields)
        # Normalized 0.00 == 0 and 0000003f == 3f should NOT produce false mismatches.
        self.assertNotIn(("/dev/cpuctl/top-app/cpu.uclamp.min", "value"), fields)
        self.assertNotIn(("tid:28984(qimian233.ztool)", "cpus_allowed"), fields)

        # In clean-env (touched_min_freq=False), transient scaling_min_freq value drift
        # is recorded in notes instead of failing verification, while max_freq/mode still fail.
        clean_snap = {
            **snapshot,
            "mode": "clean-env",
            "applied_mutations": {**snapshot["applied_mutations"], "touched_min_freq_policies": []},
        }
        live_clean_ok = {
            "policies": {
                "policy5": {
                    "nodes": {
                        "scaling_min_freq": {"mode": "0644", "value": "672000"},
                        "scaling_max_freq": {"mode": "0444", "value": "2035200"},
                        "walt/adaptive_high_freq": {"mode": "0444", "value": "0"},
                    }
                }
            },
            "cpuctl": {"/dev/cpuctl/top-app/cpu.uclamp.min": {"mode": "0644", "value": "0"}},
            "settings": {"system:artificial_switch_of_power_saving": "1"},
            "thread_affinities": {"28984": {"comm": "qimian233.ztool", "cpus_allowed_hex": "3f"}},
        }
        vr_clean = verify_snapshot_diff(clean_snap, live_clean_ok, mode_name="clean-env")
        self.assertTrue(vr_clean.ok)
        self.assertTrue(any("scaling_min_freq" in n for n in vr_clean.notes))

    def test_thread_and_tis_deltas(self) -> None:
        s0 = {"10:10": {"pid": 10, "tid": 10, "proc_name": "p", "comm": "t", "run_ns": 100_000_000, "delay_ns": 5_000_000, "slices": 10}}
        s1 = {"10:10": {"pid": 10, "tid": 10, "proc_name": "p", "comm": "t", "run_ns": 350_000_000, "delay_ns": 15_000_000, "slices": 30}}
        rows = compute_thread_deltas(s0, s1, elapsed_s=10.0)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["cpu_ms"], 250.0)
        self.assertEqual(rows[0]["slices_per_s"], 2.0)

        tis = compute_tis_delta(
            {"10": {"comm": "ui", "clusters": {"cpu0": {1000: 10, 2000: 10}}}},
            {"10": {"comm": "ui", "clusters": {"cpu0": {1000: 30, 2000: 90}}}},
        )
        self.assertEqual(tis["10"]["clusters"]["cpu0"]["top_frequencies"][0]["khz"], 2000)
        self.assertEqual(tis["10"]["clusters"]["cpu0"]["top_frequencies"][0]["pct"], 80.0)


if __name__ == "__main__":
    unittest.main()
