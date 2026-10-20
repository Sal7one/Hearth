#!/usr/bin/env python3
"""Prepare the pinned research checkout without modifying it; fail on patch drift."""
from pathlib import Path
import shutil,sys
source,destination=map(Path,sys.argv[1:])
if destination.exists():shutil.rmtree(destination)
shutil.copytree(source,destination,ignore=shutil.ignore_patterns('.git'))
def patch(name,old,new,count=1):
 p=destination/name;s=p.read_text();assert s.count(old)==count,(name,old,s.count(old));p.write_text(s.replace(old,new))
patch('src/models.cpp','#include \"models.h\"','#include \"models.h\"\n#include \"oute_sampling.h\"')
# Cancellation is sticky for a session. JNI recreates a cancelled engine before reuse.
patch('src/chat.h','#include <memory>','#include <memory>\n#include <atomic>')
patch('src/chat.h','        bool aborted;','        std::atomic_bool aborted;')
patch('src/models.cpp','        aborted = false;','        if (aborted.load()) throw std::runtime_error("Oute session was cancelled");')
patch('src/models.cpp','''                    aborted = true;
                    break;
                }
            }
        }

        if (aborted && !completed)''','''                    if (!completed) throw std::runtime_error("Oute generation exceeded the token limit");
                }
            }
        }

        if (aborted && !completed)''')
patch('src/models.cpp','        if (aborted && !completed)','        if (!aborted.load() && !completed) throw std::runtime_error(\"Oute generation exceeded the context limit\");\n        if (aborted && !completed)')
# CPU graph cancellation must interrupt prefill, not wait for the next token batch.
patch('src/models.cpp','''        backend_context.init(rt_config.model_gpu_layers, "main", config_.num_hidden_layers, GRAPH_SIZE, rt_config.n_threads);''','''        backend_context.init(rt_config.model_gpu_layers, "main", config_.num_hidden_layers, GRAPH_SIZE, rt_config.n_threads);
        backend_context.set_abort_callback(nullptr, BaseModel::is_aborted, this);''')
patch('src/models.cpp','''        return run_model(p, remain, gen_config,past, lm_logits, 1);''','''        if (aborted.load()) return false;
        return run_model(p, remain, gen_config,past, lm_logits, 1);''')
patch('src/backend.cpp','''        ggml_backend_sched_graph_compute_async(sched, gf);
        ggml_backend_sched_synchronize(sched);''','''        const auto status = ggml_backend_sched_graph_compute_async(sched, gf);
        ggml_backend_sched_synchronize(sched);
        if (status != GGML_STATUS_SUCCESS) throw std::runtime_error("Oute CPU graph failed or was cancelled");''')
# Do not run the waveform decoder after cancellation; never publish partial audio.
patch('models/oute.cpp','''            ggml::log(GGML_LOG_LEVEL_INFO, "%zd vocoder tokens generated.", tokens.size());''','''            if (aborted.load()) return;
            ggml::log(GGML_LOG_LEVEL_INFO, "%zd vocoder tokens generated.", tokens.size());''',2)
# The SDK uses an Oute-only sampler with the required bounded penalty window and min-p.
patch('src/models.cpp','    Sampler *SamplerFactory::Create(const GenerationConfig &gen_config)','''    class HearthOuteSampler : public NonGreedySampler {
    public:
        explicit HearthOuteSampler(const GenerationConfig &c) : NonGreedySampler(c,c.temperature,c.top_k), top_p(c.top_p) {}
    protected:
        void do_sampling(float *logits, const int) override {
            hearth::oute::filter_probabilities(token_scores,top_p);
            for(const auto &t:token_scores)logits[t.id]=t.score;
        }
        float top_p;
    };
    Sampler *SamplerFactory::Create(const GenerationConfig &gen_config)''')
patch('src/models.cpp','''            if (gen_config.sampling == "top_p")''','''            if (gen_config.sampling == "hearth_oute")
                r = new HearthOuteSampler(gen_config);
            else if (gen_config.sampling == "top_p")''')
