#include "FileUtilsPrivatePCH.h"
#include "FileUtilsBPLibrary.h"
#include "CoreMisc.h"
#include "Paths.h"

DEFINE_LOG_CATEGORY_STATIC(LogFileUtils, Log, All);

UFileUtilsBPLibrary::UFileUtilsBPLibrary(const FObjectInitializer& ObjectInitializer) 
: Super(ObjectInitializer)
{

}

bool UFileUtilsBPLibrary::ReadFile(const FString &FileName, TArray<uint8> &Bytes)
{
	Bytes.Empty();
	const FString Path = FPaths::Combine(FPaths::GameUserDir().GetCharArray().GetData(), FileName.GetCharArray().GetData());
	if (!FPlatformFileManager::Get().GetPlatformFile().FileExists(*Path))
	{
		UE_LOG(LogFileUtils, Error, TEXT("File doesn't exist: %s"), *Path);
		return false;
	}
	const int64 FileSize = FPlatformFileManager::Get().GetPlatformFile().FileSize(*Path);
	Bytes.AddUninitialized((int32)FileSize);
	IPlatformFile& PlatformFile = FPlatformFileManager::Get().GetPlatformFile();
	IFileHandle* FileHandle = PlatformFile.OpenRead(*Path);
	if (FileHandle)
	{
		bool Result = FileHandle->Read(Bytes.GetData(), Bytes.Num());
		delete FileHandle;
		if (!Result)
		{
			UE_LOG(LogFileUtils, Error, TEXT("Couldn't read file: %s"), *Path);
			return false;
		}
	}
	else
	{
		UE_LOG(LogFileUtils, Error, TEXT("Couldn't open file: %s"), *Path);
		return false;
	}
	return true;

}

