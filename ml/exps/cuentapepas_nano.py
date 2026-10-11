#!/usr/bin/env python3
# Adapted from YOLOX exps/default/yolox_nano.py (Copyright (c) Megvii, Inc. and its affiliates, Apache-2.0).
"""YOLOX-Nano experiment for Cuentapepas: 5 maturity classes, 384x640 (HxW) landscape input.

Loaded by YOLOX `get_exp` from this file path (it is not part of the `cuentapepas` package). Environment overrides:
CP_DATASET_DIR (COCO dataset root), CP_MAX_EPOCH, CP_WORKERS, CP_NO_AUG_EPOCHS and CP_MULTISCALE
("0" trains at the fixed 384x640 size: multiscale up to 640 rows needs >3 GB and spills to shared memory on a
3 GB GTX 1050, which makes each iteration 2-3x slower).
"""
import os
from pathlib import Path

import torch.nn as nn

from yolox.exp import Exp as MyExp

REPO_ROOT = Path(__file__).resolve().parents[2]


class Exp(MyExp):
    def __init__(self):
        super().__init__()
        self.num_classes = 5
        self.depth = 0.33
        self.width = 0.25
        self.act = "silu"

        # (height, width): the app runs in landscape on 1280x720 frames letterboxed to 640x360 (+24 rows of
        # padding); 384x640 keeps strides 8/16/32 exact.
        self.input_size = (384, 640)
        self.test_size = (384, 640)
        # YOLOX always calls random_resize (randint(*random_size)), so "fixed size" is the degenerate range
        # (12, 12): 12*32 = 384 rows and 32*int(12*640/384) = 640 columns.
        self.random_size = (12, 12) if os.environ.get("CP_MULTISCALE", "1") == "0" else (10, 20)
        self.mosaic_scale = (0.5, 1.5)
        self.mosaic_prob = 1.0
        self.enable_mixup = False
        # Croppie originals carry up to ~390 boxes (p99 ~140); YOLOX caps a training sample at 120 labels and
        # silently drops the rest, which would then be trained as background.
        self.max_labels = 500

        self.data_dir = os.environ.get("CP_DATASET_DIR", str(REPO_ROOT / "data" / "local" / "processed" / "croppie_v1"))
        self.train_ann = "train.json"
        self.val_ann = "val.json"

        self.max_epoch = int(os.environ.get("CP_MAX_EPOCH", 120))
        self.no_aug_epochs = int(os.environ.get("CP_NO_AUG_EPOCHS", 15))
        self.data_num_workers = int(os.environ.get("CP_WORKERS", 2))
        self.eval_interval = 10
        self.print_interval = 50

        self.exp_name = "cuentapepas_nano"
        self.output_dir = str(REPO_ROOT / "runs" / "yolox")

    def merge(self, cfg_list):
        super().merge(cfg_list)
        # BaseExp.seed defaults to None, so YOLOX keeps a "seed" opt as str; samplers and RNGs need an int.
        if isinstance(self.seed, str):
            self.seed = int(self.seed)

    def get_model(self, sublinear=False):
        def init_yolo(M):
            for m in M.modules():
                if isinstance(m, nn.BatchNorm2d):
                    m.eps = 1e-3
                    m.momentum = 0.03

        if "model" not in self.__dict__:
            from yolox.models import YOLOX, YOLOPAFPN, YOLOXHead

            in_channels = [256, 512, 1024]
            # Nano uses depthwise convolutions, the main difference from the other YOLOX sizes.
            backbone = YOLOPAFPN(self.depth, self.width, in_channels=in_channels, act=self.act, depthwise=True)
            head = YOLOXHead(self.num_classes, self.width, in_channels=in_channels, act=self.act, depthwise=True)
            self.model = YOLOX(backbone, head)

        self.model.apply(init_yolo)
        self.model.head.initialize_biases(1e-2)
        return self.model

    def get_dataset(self, cache: bool = False, cache_type: str = "ram"):
        from yolox.data import COCODataset, TrainTransform

        return COCODataset(
            data_dir=self.data_dir,
            json_file=self.train_ann,
            name="train",
            img_size=self.input_size,
            preproc=TrainTransform(max_labels=self.max_labels, flip_prob=self.flip_prob, hsv_prob=self.hsv_prob),
            cache=cache,
            cache_type=cache_type,
        )

    def get_data_loader(self, batch_size, is_distributed, no_aug=False, cache_img=None):
        loader = super().get_data_loader(batch_size, is_distributed, no_aug=no_aug, cache_img=cache_img)
        # The base class hard-codes TrainTransform(max_labels=120) for the mosaic wrapper; workers have not been
        # started yet, so raising the cap here reaches every worker copy of the dataset.
        self.dataset.preproc.max_labels = self.max_labels
        return loader

    def get_eval_dataset(self, **kwargs):
        from yolox.data import COCODataset, ValTransform

        return COCODataset(
            data_dir=self.data_dir,
            json_file=self.val_ann,
            name="val",
            img_size=self.test_size,
            preproc=ValTransform(legacy=kwargs.get("legacy", False)),
        )
