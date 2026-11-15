// Windows system output as 16 kHz mono PCM16, over the same pipe protocol as the macOS helper.
// stdout: HSA1, then u32le(byteCount)+owned PCM16 mono16k; zero length ends a normal stream.
// No microphone, screen or file is opened. Hearth's own process tree is excluded when
// Windows provides process loopback, so spoken translations are not captured again.
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <audioclient.h>
#include <audioclientactivationparams.h>
#include <mmdeviceapi.h>
#include <wrl/client.h>
#include <wrl/implements.h>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <io.h>
#include <memory>
#include <sstream>
#include <thread>
#include <vector>
#include "system_audio_pcm.h"
#include "system_audio_resample.h"

using Microsoft::WRL::ComPtr;

namespace {
// Process loopback is documented from this build; earlier Windows 10 releases may still provide it.
constexpr DWORD kProcessLoopbackBuild = 20348;
const GUID kSubtypePcm{0x00000001, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
const GUID kSubtypeFloat{0x00000003, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};

std::string describe(const char* operation, HRESULT result) {
    std::ostringstream message;
    if (result == AUDCLNT_E_DEVICE_INVALIDATED) message << "The Windows playback device changed or was removed; start captions again. ";
    message << operation << " (HRESULT 0x" << std::hex << static_cast<unsigned long>(result) << ")";
    return message.str();
}
void check(HRESULT result, const char* operation) { if (FAILED(result)) throw std::runtime_error(describe(operation, result)); }

DWORD windowsBuild() {
    using Query = LONG(WINAPI*)(OSVERSIONINFOW*);
    const HMODULE ntdll = GetModuleHandleW(L"ntdll.dll");
    const auto query = ntdll ? reinterpret_cast<Query>(GetProcAddress(ntdll, "RtlGetVersion")) : nullptr;
    OSVERSIONINFOW version{}; version.dwOSVersionInfoSize = sizeof(version);
    return query && query(&version) == 0 ? version.dwBuildNumber : 0;
}

struct Format { unsigned rate = 0, channels = 0; hearth::SystemAudioSample sample = hearth::SystemAudioSample::Int16; };

class Activation : public Microsoft::WRL::RuntimeClass<Microsoft::WRL::RuntimeClassFlags<Microsoft::WRL::ClassicCom>,
                                                      Microsoft::WRL::FtmBase, IActivateAudioInterfaceCompletionHandler> {
public:
    Activation() : done_(CreateEventW(nullptr, TRUE, FALSE, nullptr)) {}
    ~Activation() { if (done_) CloseHandle(done_); }
    STDMETHOD(ActivateCompleted)(IActivateAudioInterfaceAsyncOperation*) override { SetEvent(done_); return S_OK; }
    bool wait(DWORD milliseconds) const { return done_ && WaitForSingleObject(done_, milliseconds) == WAIT_OBJECT_0; }
private:
    HANDLE done_;
};

/** Everything rendered on this PC except the given process tree. The caller chooses the format. */
HRESULT openProcessLoopback(DWORD excludedProcess, ComPtr<IAudioClient>& client, Format& format) {
    AUDIOCLIENT_ACTIVATION_PARAMS parameters{};
    parameters.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
    parameters.ProcessLoopbackParams.ProcessLoopbackMode = PROCESS_LOOPBACK_MODE_EXCLUDE_TARGET_PROCESS_TREE;
    parameters.ProcessLoopbackParams.TargetProcessId = excludedProcess;
    PROPVARIANT activation{}; activation.vt = VT_BLOB;
    activation.blob.cbSize = sizeof(parameters); activation.blob.pBlobData = reinterpret_cast<BYTE*>(&parameters);
    const auto handler = Microsoft::WRL::Make<Activation>();
    if (!handler) return E_OUTOFMEMORY;
    ComPtr<IActivateAudioInterfaceAsyncOperation> operation;
    HRESULT result = ActivateAudioInterfaceAsync(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK, __uuidof(IAudioClient), &activation, handler.Get(), &operation);
    if (FAILED(result)) return result;
    if (!handler->wait(10000)) return HRESULT_FROM_WIN32(ERROR_TIMEOUT);
    HRESULT activated = E_FAIL; ComPtr<IUnknown> unknown;
    result = operation->GetActivateResult(&activated, &unknown);
    if (FAILED(result)) return result;
    if (FAILED(activated)) return activated;
    result = unknown.As(&client);
    if (FAILED(result)) return result;
    WAVEFORMATEX wave{}; wave.wFormatTag = WAVE_FORMAT_PCM; wave.nChannels = 2; wave.nSamplesPerSec = 48000; wave.wBitsPerSample = 16;
    wave.nBlockAlign = static_cast<WORD>(wave.nChannels * wave.wBitsPerSample / 8); wave.nAvgBytesPerSec = wave.nSamplesPerSec * wave.nBlockAlign;
    result = client->Initialize(AUDCLNT_SHAREMODE_SHARED,
        AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM,
        2000000, 0, &wave, nullptr);
    if (FAILED(result)) { client.Reset(); return result; }
    format = Format{wave.nSamplesPerSec, wave.nChannels, hearth::SystemAudioSample::Int16};
    return S_OK;
}

/** The default playback device's whole mix, for Windows releases without process loopback. */
void openEndpointLoopback(ComPtr<IAudioClient>& client, Format& format) {
    ComPtr<IMMDeviceEnumerator> enumerator;
    check(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&enumerator)), "Windows audio devices are unavailable");
    ComPtr<IMMDevice> device;
    const HRESULT found = enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device);
    if (found == HRESULT_FROM_WIN32(ERROR_NOT_FOUND)) throw std::runtime_error("No Windows playback device is available for PC audio capture");
    check(found, "Cannot open the default Windows playback device");
    check(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, &client), "Cannot open Windows audio loopback");
    WAVEFORMATEX* mix = nullptr;
    check(client->GetMixFormat(&mix), "Cannot read the Windows playback format");
    const std::unique_ptr<WAVEFORMATEX, void(*)(WAVEFORMATEX*)> owned(mix, [](WAVEFORMATEX* value) { CoTaskMemFree(value); });
    bool floating = mix->wFormatTag == WAVE_FORMAT_IEEE_FLOAT, integer = mix->wFormatTag == WAVE_FORMAT_PCM;
    if (mix->wFormatTag == WAVE_FORMAT_EXTENSIBLE && mix->cbSize >= sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX)) {
        const auto* extensible = reinterpret_cast<const WAVEFORMATEXTENSIBLE*>(mix);
        floating = IsEqualGUID(extensible->SubFormat, kSubtypeFloat) != 0; integer = IsEqualGUID(extensible->SubFormat, kSubtypePcm) != 0;
    }
    if (floating && mix->wBitsPerSample == 32) format.sample = hearth::SystemAudioSample::Float32;
    else if (integer && mix->wBitsPerSample == 16) format.sample = hearth::SystemAudioSample::Int16;
    else if (integer && mix->wBitsPerSample == 24) format.sample = hearth::SystemAudioSample::Int24;
    else if (integer && mix->wBitsPerSample == 32) format.sample = hearth::SystemAudioSample::Int32;
    else throw std::runtime_error("Windows returned an unsupported playback sample format");
    format.rate = mix->nSamplesPerSec; format.channels = mix->nChannels;
    if (mix->nBlockAlign != format.channels * hearth::systemAudioSampleBytes(format.sample)) throw std::runtime_error("Windows returned an unsupported playback frame layout");
    check(client->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK, 2000000, 0, mix, nullptr),
          "Cannot start Windows audio loopback");
}

bool writeBytes(HANDLE output, const uint8_t* bytes, size_t count, const std::shared_ptr<hearth::SystemAudioQueue>& queue) {
    while (count) {
        if (!queue->error().empty()) return false;
        DWORD written = 0;
        if (!WriteFile(output, bytes, static_cast<DWORD>(count), &written, nullptr) || !written) {
            queue->fail("System audio receiver closed its PCM pipe"); return false;
        }
        bytes += written; count -= written;
    }
    return true;
}
bool writeSize(HANDLE output, size_t count, const std::shared_ptr<hearth::SystemAudioQueue>& queue) {
    const uint8_t bytes[]{static_cast<uint8_t>(count), static_cast<uint8_t>(count >> 8), static_cast<uint8_t>(count >> 16), static_cast<uint8_t>(count >> 24)};
    return writeBytes(output, bytes, sizeof(bytes), queue);
}

struct Control {
    std::shared_ptr<hearth::SystemAudioQueue> queue = std::make_shared<hearth::SystemAudioQueue>();
    HANDLE stop = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    void requestStop() const { SetEvent(stop); }
    void fail(std::string message) const { queue->fail(std::move(message)); SetEvent(stop); }
};

/** STOP or a closed control pipe ends capture normally; the owner then drains the final frame. */
void readCommands(const std::shared_ptr<Control>& control) {
    const HANDLE input = GetStdHandle(STD_INPUT_HANDLE);
    std::string command; char bytes[64];
    while (true) {
        DWORD count = 0;
        if (!ReadFile(input, bytes, sizeof(bytes), &count, nullptr) || !count) { control->requestStop(); return; }
        for (DWORD i = 0; i < count; ++i) {
            if (bytes[i] == '\n') {
                if (command == "STOP") control->requestStop(); else { control->fail("Invalid system audio control command"); return; }
                command.clear();
            } else if (bytes[i] != '\r') {
                command += bytes[i];
                if (command.size() > 16) { control->fail("Oversized system audio control command"); return; }
            }
        }
    }
}

void capture(DWORD parent, const std::shared_ptr<Control>& control) {
    ComPtr<IAudioClient> client; Format format;
    const HRESULT process = openProcessLoopback(parent, client, format);
    if (FAILED(process)) {
        // An older Windows 10 without process loopback still captures the mix, including Hearth's own voice output.
        if (windowsBuild() >= kProcessLoopbackBuild) throw std::runtime_error(describe("Cannot start Windows PC audio capture", process));
        client.Reset(); openEndpointLoopback(client, format);
    }
    const HANDLE packets = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (!packets) throw std::runtime_error("Cannot create the Windows audio event");
    const std::unique_ptr<void, void(*)(void*)> ownedPackets(packets, [](void* handle) { CloseHandle(handle); });
    check(client->SetEventHandle(packets), "Cannot attach the Windows audio event");
    ComPtr<IAudioCaptureClient> reader;
    check(client->GetService(IID_PPV_ARGS(&reader)), "Cannot read Windows audio loopback");
    const HANDLE owner = OpenProcess(SYNCHRONIZE, FALSE, parent);
    if (!owner) throw std::runtime_error("The Hearth process that requested PC audio is not running");
    const std::unique_ptr<void, void(*)(void*)> ownedOwner(owner, [](void* handle) { CloseHandle(handle); });

    hearth::SystemAudioResampler resampler(format.rate);
    hearth::SystemAudioPcm pcm([queue = control->queue](const uint8_t* bytes, size_t size) { return queue->push(bytes, size); });
    std::vector<float> mono, resampled;
    const auto deliver = [&] {
        resampled.clear(); resampler.push(mono.data(), mono.size(), resampled);
        if (!resampled.empty()) pcm.floats(resampled.data(), resampled.size());
    };
    LARGE_INTEGER frequency, now; QueryPerformanceFrequency(&frequency);
    const auto ticks = [&] { QueryPerformanceCounter(&now); return now.QuadPart; };
    check(client->Start(), "Cannot start Windows audio loopback");
    control->queue->ready();
    // Loopback delivers nothing while the PC is silent. Recognition still needs real-time
    // silence to end an utterance, so quiet gaps are filled from the monotonic clock.
    const LONGLONG gap = frequency.QuadPart / 10, slack = frequency.QuadPart / 50;
    LONGLONG covered = ticks();
    const HANDLE waits[]{control->stop, owner, packets};
    while (control->queue->error().empty()) {
        const DWORD signal = WaitForMultipleObjects(3, waits, FALSE, 20);
        if (signal == WAIT_OBJECT_0 || signal == WAIT_OBJECT_0 + 1) break;
        if (signal == WAIT_FAILED) throw std::runtime_error("Windows audio capture wait failed");
        bool received = false; UINT32 next = 0; HRESULT result;
        while (SUCCEEDED(result = reader->GetNextPacketSize(&next)) && next) {
            BYTE* data = nullptr; UINT32 frames = 0; DWORD flags = 0;
            check(reader->GetBuffer(&data, &frames, &flags, nullptr, nullptr), "Cannot read a Windows audio packet");
            try {
                if (flags & AUDCLNT_BUFFERFLAGS_SILENT) mono.assign(frames, 0.0f);
                else hearth::systemAudioDownmix(data, frames, format.channels, format.sample, mono);
            } catch (...) { reader->ReleaseBuffer(frames); throw; }
            check(reader->ReleaseBuffer(frames), "Cannot release a Windows audio packet");
            deliver(); received = true;
        }
        check(result, "Windows audio loopback stopped");
        const LONGLONG current = ticks();
        if (received) covered = current;
        else if (current - covered > gap) {
            const LONGLONG silent = current - covered - slack;
            mono.assign(static_cast<size_t>(silent * format.rate / frequency.QuadPart), 0.0f);
            deliver(); covered = current - slack;
        }
    }
    client->Stop();
    if (control->queue->error().empty()) pcm.finish();
}
}

int main(int argc, char** argv) {
    _setmode(_fileno(stdout), _O_BINARY); _setmode(_fileno(stdin), _O_BINARY);
    if (argc == 2 && std::strcmp(argv[1], "--probe") == 0) {
        std::printf("HEARTH_SYSTEM_AUDIO_PROTOCOL=1\nAVAILABLE=1\nPERMISSION=not-requested\nSAMPLE_RATE=16000\nCHANNELS=1\nEXCLUDES_HEARTH_PROCESS=%d\n",
                    windowsBuild() >= kProcessLoopbackBuild ? 1 : 0);
        return 0;
    }
    if (argc != 3 || std::strcmp(argv[1], "capture") != 0) { std::fprintf(stderr, "Usage: hearth-system-audio --probe | capture parent-pid\n"); return 2; }
    char* end = nullptr; const unsigned long parent = std::strtoul(argv[2], &end, 10);
    if (!end || *end || parent == 0 || parent > 0x7fffffffUL) { std::fprintf(stderr, "Invalid Hearth process id\n"); return 2; }
    const auto control = std::make_shared<Control>();
    if (!control->stop) { std::fprintf(stderr, "Cannot create the system audio stop event\n"); return 1; }
    const auto queue = control->queue;
    std::atomic<bool> written{false};
    std::thread writer([queue, &written] {
        const HANDLE output = GetStdHandle(STD_OUTPUT_HANDLE);
        if (queue->waitReady()) {
            const uint8_t magic[]{'H', 'S', 'A', '1'};
            bool open = writeBytes(output, magic, sizeof(magic), queue);
            while (open) {
                // The queue lends the frame until the next pop; it is written before that.
                const auto frame = queue->pop();
                if (!frame.size) break;
                open = writeSize(output, frame.size, queue) && writeBytes(output, frame.bytes, frame.size, queue);
            }
            if (open && queue->error().empty()) (void)writeSize(output, 0, queue);
        }
        written = true;
    });
    std::thread(readCommands, control).detach();
    const HRESULT com = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    try {
        check(com, "COM initialization failed");
        capture(static_cast<DWORD>(parent), control);
    } catch (const std::exception& failure) { queue->fail(failure.what()); }
    if (SUCCEEDED(com)) CoUninitialize();
    queue->finish();
    // A failed stream never waits for a receiver that stopped reading its pipe.
    while (!written.load()) {
        if (!queue->error().empty()) CancelSynchronousIo(writer.native_handle());
        Sleep(20);
    }
    writer.join();
    const auto error = queue->error();
    if (!error.empty()) { std::fprintf(stderr, "%s\n", error.c_str()); return 1; }
    return 0;
}
