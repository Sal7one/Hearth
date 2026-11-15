import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("hearth_windows_stages", Path(__file__).with_name("windows_stages.py"))
stages = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(stages)


class NemotronCudaTest(unittest.TestCase):
    def test_portable_code_is_always_built_and_native_code_only_for_gpus_that_can_run_it(self):
        self.assertEqual("80-virtual", stages.nemotron_cuda_architectures())
        self.assertEqual("80-virtual", stages.nemotron_cuda_architectures(""))
        self.assertEqual("80-virtual;86-real;89-real;120a-real", stages.nemotron_cuda_architectures("86-real;89-real;120a-real"))
        self.assertEqual("80-virtual;86-real;121a-real", stages.nemotron_cuda_architectures(" 86-real , 121a-real "))
        # Older than compute capability 8.0, portable duplicates, repeats and CMake's "native" are not native code for a named GPU.
        for refused in ("75-real", "61-real", "86-virtual", "80-virtual", "86-real;86-real", "native", "86"):
            with self.subTest(refused), self.assertRaises(ValueError):
                stages.nemotron_cuda_architectures(refused)

    def test_the_gpu_backend_may_import_only_windows_the_driver_and_its_own_kernels(self):
        self.assertEqual("nvcuda.dll", stages.NVIDIA_DRIVER)
        self.assertEqual({"backend": "hearth_nemotron_cuda.dll", "blas": "hearth_nemotron_cublas.dll", "notices": "Nemotron-CUDA-NOTICES.txt"},
                         stages.NEMOTRON_CUDA)
        self.assertNotIn(stages.NVIDIA_DRIVER, stages.SYSTEM_DLLS, "The driver is allowed by name for a GPU library, never as a Windows component")

    def test_the_adapter_copy_stops_the_build_when_the_shared_source_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "adapter.cpp"
            source.write_text("backend.gpu = -1;\n", encoding="utf-8")
            copy = stages.prepared_copy(source, Path(temporary) / "out/adapter.cpp", [("backend.gpu = -1;", "backend.gpu = chosen;")], prefix='#include "nemotron_cuda.h"')
            self.assertEqual('#include "nemotron_cuda.h"\nbackend.gpu = chosen;\n', copy.read_text(encoding="utf-8"))
            source.write_text("backend.gpu = -1; backend.gpu = -1;\n", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "changed; review"):
                stages.prepared_copy(source, Path(temporary) / "out/adapter.cpp", [("backend.gpu = -1;", "backend.gpu = chosen;")])
        adapter = (stages.CPP / "speech/backends/nemotron_backend.cpp").read_text(encoding="utf-8")
        for needle in ("backend.gpu = -1;", "    std::string language;\n", '[](void*) { return "CPU · NeMo GGML"; }'):
            self.assertEqual(1, adapter.count(needle), f"The pinned adapter no longer has exactly one: {needle!r}")


if __name__ == "__main__":
    unittest.main()
